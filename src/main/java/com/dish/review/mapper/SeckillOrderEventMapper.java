package com.dish.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dish.review.entity.SeckillOrderEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 秒杀消息事件表的 MyBatis-Plus 数据访问接口。
 */
public interface SeckillOrderEventMapper
        extends BaseMapper<SeckillOrderEvent> {

    /**
     * 消费事务内锁定事件行，配合状态检查实现回滚并发保护。
     */
    @Select("SELECT * FROM tb_seckill_order_event WHERE event_id = #{eventId} FOR UPDATE")
    SeckillOrderEvent selectByEventIdForUpdate(@Param("eventId") String eventId);
}
