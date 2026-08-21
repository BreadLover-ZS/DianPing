package com.dish.review.mq;

import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.service.SeckillOrderEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 补偿生产者无法确定发送结果的秒杀订单事件。
 *
 * <p>只有 PENDING/PUBLISH_UNKNOWN 且到达 next_retry_time 的事件会被处理；
 * 事件本身保留原 orderId/eventId，重发仍然依靠消费端幂等。</p>
 */
@Slf4j
@Component
public class SeckillOrderPublishRetryTask {

    private final SeckillOrderEventService eventService;
    private final SeckillOrderPublisher orderPublisher;

    /**
     * 注入事件查询服务和生产者，补偿时复用正常发布入口。
     */
    public SeckillOrderPublishRetryTask(
            SeckillOrderEventService eventService,
            SeckillOrderPublisher orderPublisher) {
        this.eventService = eventService;
        this.orderPublisher = orderPublisher;
    }

    /**
     * 周期扫描到期事件；快速补偿耗尽后转为低频慢重试，直到回调使状态收敛。
     */
    @Scheduled(
            fixedDelayString = "${dish-review.seckill.publish-retry-delay:1000}"
    )
    public void retryDueEvents() {
        List<SeckillOrderEvent> events = eventService.findDueRetries(20);

        for (SeckillOrderEvent event : events) {
            if (!eventService.claimForRetry(event)) {
                continue;
            }

            SeckillOrderMessage message = toMessage(event);

            try {
                orderPublisher.publish(message);
                log.info(
                        "已重新投递秒杀订单事件，eventId={}，status={}，retryCount={}",
                        event.getEventId(),
                        event.getStatus(),
                        event.getRetryCount()
                );
            } catch (Exception exception) {
                boolean handled = eventService.scheduleRetry(
                        event.getEventId(),
                        exception.getMessage()
                );

                if (!handled) {
                    log.error(
                            "秒杀订单事件无法安排下一次补偿，eventId={}",
                            event.getEventId(),
                            exception
                    );
                }
            }
        }
    }

    /**
     * 用持久化事件重建原消息，保持 eventId 和 orderId 不变以支持幂等。
     */
    private SeckillOrderMessage toMessage(SeckillOrderEvent event) {
        return new SeckillOrderMessage(
                event.getEventId(),
                event.getOrderId(),
                event.getUserId(),
                event.getVoucherId(),
                event.getCreatedAt(),
                event.getMessageVersion()
        );
    }
}
