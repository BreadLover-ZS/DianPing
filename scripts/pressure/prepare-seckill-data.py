#!/usr/bin/env python3
"""Prepare isolated MySQL/Redis data for an AutoDL pressure-test run.

The script is intentionally dependency-free: it only shells out to mysql and
redis-cli already installed on the target machine. Token JSON stays on the
target machine and is copied directly to the load generator when needed.
"""

import argparse
import json
import subprocess
import time
import uuid
from pathlib import Path


def run(command, stdin=None):
    result = subprocess.run(
        command,
        input=stdin,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return result.stdout.strip()


def mysql(args, statement, database=None):
    command = ['mysql', '-N', '-B'] + args.mysql_args
    if database:
        command.append(database)
    command += ['-e', statement]
    return run(command)


def resp_command(*parts):
    encoded = [str(part).encode() for part in parts]
    payload = [f"*{len(encoded)}\r\n".encode()]
    for part in encoded:
        payload.append(f"${len(part)}\r\n".encode())
        payload.append(part)
        payload.append(b"\r\n")
    return b"".join(payload)


def redis(args, *parts):
    command = ['redis-cli']
    if args.redis_db is not None:
        command += ['-n', str(args.redis_db)]
    if args.redis_password:
        command += ['-a', args.redis_password]
    command += [str(part) for part in parts]
    return run(command)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--database', default='dish_review_pressure_retest')
    parser.add_argument('--users', type=int, default=60000)
    parser.add_argument('--stock', type=int, default=60000)
    parser.add_argument('--redis-db', type=int, default=5)
    parser.add_argument('--token-file', required=True)
    parser.add_argument('--mysql-args', nargs='*', default=[])
    parser.add_argument('--mysql-user', default='root')
    parser.add_argument('--mysql-password', default='')
    parser.add_argument('--mysql-host', default='')
    parser.add_argument('--redis-password', default='')
    args = parser.parse_args()
    if not args.mysql_args:
        args.mysql_args = ['-u' + args.mysql_user]
        if args.mysql_password:
            args.mysql_args.append('-p' + args.mysql_password)
        if args.mysql_host:
            args.mysql_args.append('-h' + args.mysql_host)

    # tb_user.phone is varchar(11); keep the generated range within 11 digits.
    base_phone = 19900000000 + (int(time.time()) % 1000) * 1000
    values = []
    for index in range(1, args.users + 1):
        phone = str(base_phone + index)
        nickname = f"pressure_retest_{index}"
        values.append("('{}','','{}','','USER',NOW(),NOW())".format(phone, nickname))

    for offset in range(0, len(values), 1000):
        chunk = values[offset:offset + 1000]
        statement = (
            'INSERT IGNORE INTO tb_user '
            '(phone,password,nick_name,icon,role,create_time,update_time) VALUES '
            + ','.join(chunk) + ';'
        )
        mysql(args, statement, args.database)

    lower = str(base_phone + 1)
    upper = str(base_phone + args.users)
    user_rows = mysql(
        args,
        "SELECT id FROM tb_user WHERE phone >= '{}' AND phone <= '{}' ORDER BY phone LIMIT {}".format(
            lower, upper, args.users
        ),
        args.database,
    ).splitlines()
    if len(user_rows) != args.users:
        raise RuntimeError(f'expected {args.users} pressure users, got {len(user_rows)}')

    title = 'pressure-retest-{}'.format(int(time.time()))
    voucher_id = int(mysql(
        args,
        "INSERT INTO tb_voucher "
        "(shop_id,title,sub_title,rules,pay_value,actual_value,type,status,create_time,update_time) "
        "VALUES (1,'{}','双机压测券','仅用于隔离压测',100,1000,1,1,NOW(),NOW()); "
        'SELECT LAST_INSERT_ID();'.format(title),
        args.database,
    ).splitlines()[-1])
    mysql(
        args,
        "INSERT INTO tb_seckill_voucher "
        '(voucher_id,stock,create_time,begin_time,end_time,update_time) '
        'VALUES ({},{},NOW(),NOW()-INTERVAL 1 MINUTE,NOW()+INTERVAL 2 HOUR,NOW());'.format(
            voucher_id, args.stock
        ),
        args.database,
    )

    keys = [
        f'seckill:stock:{{{voucher_id}}}',
        f'seckill:order:{{{voucher_id}}}',
        f'seckill:reservation:{{{voucher_id}}}',
        f'seckill:reservation:user:{{{voucher_id}}}',
        f'seckill:reservation:pending:{{{voucher_id}}}',
        f'seckill:reservation:order:{{{voucher_id}}}',
    ]
    redis(args, 'DEL', *keys)
    redis(args, 'SET', keys[0], args.stock)

    tokens = []
    pipeline = subprocess.Popen(
        ['redis-cli'] + (['-n', str(args.redis_db)] if args.redis_db is not None else [])
        + (['-a', args.redis_password] if args.redis_password else []) + ['--pipe'],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert pipeline.stdin is not None
    for user_id in user_rows:
        token = uuid.uuid4().hex
        tokens.append(token)
        token_key = f'login:token:{token}'
        pipeline.stdin.write(resp_command('HSET', token_key, 'id', user_id,
                                          'nickName', f'pressure_{user_id}', 'icon', '', 'role', 'USER'))
        pipeline.stdin.write(resp_command('EXPIRE', token_key, 7200))
    pipeline.stdin.close()
    pipeline.wait()
    if pipeline.returncode != 0:
        stderr = pipeline.stderr.read().decode(errors='replace') if pipeline.stderr else ''
        raise RuntimeError(stderr)

    token_path = Path(args.token_file)
    token_path.parent.mkdir(parents=True, exist_ok=True)
    token_path.write_text(json.dumps(tokens, ensure_ascii=False), encoding='utf-8')
    print(json.dumps({'database': args.database, 'redisDb': args.redis_db,
                      'voucherId': voucher_id, 'users': len(tokens),
                      'tokenFile': str(token_path)}))


if __name__ == '__main__':
    main()
