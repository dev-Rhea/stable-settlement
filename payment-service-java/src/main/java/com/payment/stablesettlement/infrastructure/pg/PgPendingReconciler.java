package com.payment.stablesettlement.infrastructure.pg;

import com.payment.stablesettlement.application.service.PaymentTimeoutService;
import com.payment.stablesettlement.application.service.PgCallbackService;
import com.payment.stablesettlement.application.service.PgClient;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.interfaces.dto.pg.PgCallbackRequest;
import com.payment.stablesettlement.interfaces.dto.pg.PgResult;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PG 콜백은 유실될 수 있다. PENDING 으로 오래 머무른 결제를 주기적으로 재조회해
 * 결과가 나와 있으면 콜백과 동일한 경로로 확정하고, 끝내 응답이 없으면 TIMEOUT 으로 정리한다.
 */
@Slf4j
@Component
public class PgPendingReconciler {

    private static final String RECONCILE_LOCK_KEY = "pg:reconcile:lock";
    private static final long RECONCILE_LOCK_LEASE_SECONDS = 60;

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final PgCallbackService pgCallbackService;
    private final PaymentTimeoutService paymentTimeoutService;
    private final RedissonClient redissonClient;
    private final Clock clock;
    private final long inquiryAfterSeconds;
    private final long giveUpAfterSeconds;

    public PgPendingReconciler(PaymentRepository paymentRepository,
                               PgClient pgClient,
                               PgCallbackService pgCallbackService,
                               PaymentTimeoutService paymentTimeoutService,
                               RedissonClient redissonClient,
                               Clock clock,
                               @Value("${pg.reconcile.inquiry-after-seconds}") long inquiryAfterSeconds,
                               @Value("${pg.reconcile.give-up-after-seconds}") long giveUpAfterSeconds) {
        this.paymentRepository = paymentRepository;
        this.pgClient = pgClient;
        this.pgCallbackService = pgCallbackService;
        this.paymentTimeoutService = paymentTimeoutService;
        this.redissonClient = redissonClient;
        this.clock = clock;
        this.inquiryAfterSeconds = inquiryAfterSeconds;
        this.giveUpAfterSeconds = giveUpAfterSeconds;
    }

    @Scheduled(fixedDelayString = "${pg.reconcile.fixed-delay-ms}")
    public void reconcilePendingPayments() {
        RLock lock = redissonClient.getLock(RECONCILE_LOCK_KEY);

        boolean acquired;
        try {
            acquired = lock.tryLock(0, RECONCILE_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("PG reconcile lock acquisition interrupted");
            return;
        }

        if (!acquired) {
            log.debug("PG reconcile lock held by another instance, skipping");
            return;
        }

        try {
            reconcile();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    void reconcile() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime inquiryThreshold = now.minusSeconds(inquiryAfterSeconds);
        LocalDateTime giveUpThreshold = now.minusSeconds(giveUpAfterSeconds);

        List<Payment> stalePayments = paymentRepository
            .findTop100ByStatusAndPgRequestedAtBeforeOrderByPgRequestedAtAsc(
                PaymentStatus.PENDING, inquiryThreshold);

        for (Payment payment : stalePayments) {
            try {
                reconcileOne(payment, giveUpThreshold);
            } catch (Exception e) {
                log.error("Failed to reconcile pending payment: reference = {}",
                    payment.getPaymentReference(), e);
            }
        }
    }

    private void reconcileOne(Payment payment, LocalDateTime giveUpThreshold) {
        Optional<PgResult> result = pgClient.queryResult(payment.getPgTransactionId());

        if (result.isPresent()) {
            PgResult pgResult = result.get();
            String reason = pgResult == PgResult.REJECTED
                ? "Rejected by PG (recovered by reconciliation)" : null;
            pgCallbackService.handleCallback(
                new PgCallbackRequest(payment.getPgTransactionId(), pgResult, reason));
            log.info("Recovered lost PG callback: reference = {}, result = {}",
                payment.getPaymentReference(), pgResult);
            return;
        }

        if (payment.getPgRequestedAt().isBefore(giveUpThreshold)) {
            paymentTimeoutService.markTimedOut(payment.getPaymentReference(),
                "No PG response within " + giveUpAfterSeconds + "s");
        }
    }
}
