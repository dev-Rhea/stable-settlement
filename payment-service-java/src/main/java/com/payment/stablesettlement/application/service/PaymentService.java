package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.application.exception.DuplicatePaymentException;
import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String LOCK_PREFIX = "payment:lock:";
    private static final long LOCK_WAIT_TIME = 5;
    private static final long LOCK_LEASE_TIME = 10;

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionService paymentTransactionService;
    private final RedissonClient redissonClient;

    public Payment processPayment(PaymentRequest request) {
        if (request.senderId().equals(request.receiverId())) {
            throw new PaymentException("SELF_PAYMENT",
                    "Sender and receiver cannot be the same");
        }

        if (paymentRepository.existsByPaymentReference(request.paymentReference())) {
            throw new DuplicatePaymentException(request.paymentReference());
        }

        String lockKey = LOCK_PREFIX + request.senderId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (!acquired) {
                throw new PaymentException("LOCK_TIMEOUT",
                        "Could not acquire lock for sender: " + request.senderId());
            }

            try {
                return paymentTransactionService.executePayment(request);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentException("LOCK_INTERRUPTED",
                    "Payment processing was interrupted", e);
        }
    }

    public Payment getPayment(String paymentReference) {
        return paymentRepository.findByPaymentReference(paymentReference)
            .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND", "payment not found: " + paymentReference));
    }
}
