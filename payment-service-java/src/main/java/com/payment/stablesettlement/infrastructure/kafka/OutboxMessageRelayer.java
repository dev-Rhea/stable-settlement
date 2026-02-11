package com.payment.stablesettlement.infrastructure.kafka;

import com.payment.stablesettlement.domain.outbox.OutboxEvent;
import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessageRelayer {

    private static final String RELAY_LOCK_KEY = "outbox:relay:lock";
    private static final long RELAY_LOCK_LEASE_SECONDS = 30;

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final RedissonClient redissonClient;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void registerGauge() {
        meterRegistry.gauge("outbox.unpublished", outboxEventRepository,
            OutboxEventRepository::countByPublishedFalse);
    }

    @Scheduled(fixedDelay = 1000)
    public void relayMessages() {
        RLock lock = redissonClient.getLock(RELAY_LOCK_KEY);

        boolean acquired;
        try {
            acquired = lock.tryLock(0, RELAY_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Outbox relay lock acquisition interrupted");
            return;
        }

        if (!acquired) {
            log.debug("Outbox relay lock held by another instance, skipping");
            return;
        }

        try {
            List<OutboxEvent> events =
                    outboxEventRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc();

            for (OutboxEvent event : events) {
                try {
                    outboxEventPublisher.publishEvent(event);
                } catch (Exception e) {
                    log.error("Failed to publish outbox event: id={}", event.getId(), e);
                    break;
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
