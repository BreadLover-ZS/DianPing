package com.dish.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀失败处置审计记录：人工重放、回滚或关闭失败记录时持久化操作者、时间和原因。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_failure_audit")
public class SeckillFailureAudit implements Serializable {

    @TableId(value = "audit_id", type = IdType.AUTO)
    private Long auditId;

    /** 关联失败记录 ID。 */
    private Long failureId;

    /** 关联事件 ID。 */
    private String eventId;

    /** REPLAY、ROLLBACK、CLOSE。 */
    private String action;

    /** 操作者标识（RBAC 完成前由调用方传入）。 */
    private String operator;

    /** 操作原因。 */
    private String reason;

    /** 操作时间。 */
    private LocalDateTime createTime;

    public static final String ACTION_REPLAY = "REPLAY";

    public static final String ACTION_ROLLBACK = "ROLLBACK";

    public static final String ACTION_CLOSE = "CLOSE";
}
