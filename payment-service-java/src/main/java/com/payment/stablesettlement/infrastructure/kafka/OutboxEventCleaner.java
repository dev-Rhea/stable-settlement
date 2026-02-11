package com.payment.stablesettlement.infrastructure.kafka;

import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventCleaner {

    private static final String CLEANUP_LOCK_KEY = "outbox:cleanup:lock";
    private static final long CLEANUP_LOCK_LEASE_SECONDS = 300;

    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    @Value("${outbox.cleanup.retention-days:7}")
    private int retentionDays;

    @Value("${outbox.cleanup.batch-size:1000}")
    private int batchSize;

    @Scheduled(cron = "${outbox.cleanup.cron:0 0 2 * * *}")
    public void cleanupPublishedEvents() {
        RLock lock = redissonClient.getLock(CLEANUP_LOCK_KEY);

        boolean acquired;
        try {
            acquired = lock.tryLock(0, CLEANUP_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Outbox cleanup lock acquisition interrupted");
            return;
        }

        if (!acquired) {
            log.debug("Outbox cleanup lock held by another instance, skipping");
            return;
        }

        try {
            doCleanup();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doCleanup() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        int totalDeleted = 0;
        int deleted;

        do {
            Integer result = transactionTemplate.execute(status ->
                    outboxEventRepository.deletePublishedBefore(cutoff, batchSize));
            deleted = result != null ? result : 0;
            totalDeleted += deleted;
        } while (deleted == batchSize);

        if (totalDeleted > 0) {
            log.info("Cleaned up {} published outbox events older than {} days",
                    totalDeleted, retentionDays);
        }
    }
}
