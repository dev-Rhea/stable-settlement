package com.payment.stablesettlement.infrastructure.kafka;

import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventCleanerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private TransactionTemplate transactionTemplate;

    private OutboxEventCleaner outboxEventCleaner;

    private final Clock clock = Clock.fixed(
            Instant.parse("2025-01-15T10:00:00Z"), ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        outboxEventCleaner = new OutboxEventCleaner(
                outboxEventRepository, clock, redissonClient, transactionTemplate);
        ReflectionTestUtils.setField(outboxEventCleaner, "retentionDays", 7);
        ReflectionTestUtils.setField(outboxEventCleaner, "batchSize", 1000);
    }

    private void setupLockAcquired() throws InterruptedException {
        when(redissonClient.getLock("outbox:cleanup:lock")).thenReturn(lock);
        when(lock.tryLock(eq(0L), eq(300L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private void setupTransactionTemplate(int... returnValues) {
        var stubbing = when(transactionTemplate.execute(any(TransactionCallback.class)));
        for (int val : returnValues) {
            stubbing = stubbing.thenReturn(val);
        }
    }

    @Test
    @DisplayName("발행된 이벤트 정리 - 배치보다 적으면 1회만 호출")
    void cleanupPublishedEvents() throws InterruptedException {
        setupLockAcquired();
        setupTransactionTemplate(500);

        outboxEventCleaner.cleanupPublishedEvents();

        verify(transactionTemplate, times(1)).execute(any());
        verify(lock).unlock();
    }

    @Test
    @DisplayName("삭제할 이벤트 없음 - 1회만 호출")
    void cleanupNoEvents() throws InterruptedException {
        setupLockAcquired();
        setupTransactionTemplate(0);

        outboxEventCleaner.cleanupPublishedEvents();

        verify(transactionTemplate, times(1)).execute(any());
    }

    @Test
    @DisplayName("여러 배치에 걸친 삭제 - batchSize만큼 반복")
    void cleanupMultipleBatches() throws InterruptedException {
        setupLockAcquired();
        setupTransactionTemplate(1000, 1000, 300);

        outboxEventCleaner.cleanupPublishedEvents();

        verify(transactionTemplate, times(3)).execute(any());
    }

    @Test
    @DisplayName("락 획득 실패 시 정리 스킵")
    void cleanupSkippedWhenLockNotAcquired() throws InterruptedException {
        when(redissonClient.getLock("outbox:cleanup:lock")).thenReturn(lock);
        when(lock.tryLock(eq(0L), anyLong(), any(TimeUnit.class))).thenReturn(false);

        outboxEventCleaner.cleanupPublishedEvents();

        verify(transactionTemplate, never()).execute(any());
    }
}
