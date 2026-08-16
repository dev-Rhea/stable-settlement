package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.interfaces.dto.pg.PgCallbackRequest;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PgCallbackService {

    private static final String LOCK_PREFIX = "payment:lock";
    private static final long LOCK_WAIT_TIME = 5;
    private static final long LOCK_LEASE_TIME = 10;

    private final PaymentRepository paymentRepository;
    private final PgCallbackTransactionService pgCallbackTransactionService;
    private final RedissonClient redissonClient;

    public void handleCallback(PgCallbackRequest request) {
        Payment payment = paymentRepository.findByPgTransactionId(request.pgTransactionId())
            .orElseThrow(() -> new PaymentException("PG_CALLBACK_PAYMENT_NOT_FOUND", "No payment found for pgTransactionId: " + request.pgTransactionId()));

        String lockKey = LOCK_PREFIX + payment.getSenderId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if(!acquired) {
                throw new PaymentException("LOCK_TIMEOUT", "Could not acquire lock for sender: " + payment.getSenderId());
            }
            try {
                pgCallbackTransactionService.finalizeCallback(request);
            } finally {
                if(lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentException("LOCK_INTERRUPTED", "PG callback processing was interrupted", e);
        }

    }
}
