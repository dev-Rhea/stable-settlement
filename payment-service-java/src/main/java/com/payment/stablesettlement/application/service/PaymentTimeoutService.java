package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.hold.BalanceHold;
import com.payment.stablesettlement.domain.hold.BalanceHoldRepository;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PG가 끝내 응답하지 않은 결제를 TIMEOUT 으로 확정하고 가예약을 해제한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTimeoutService {

    private final PaymentRepository paymentRepository;
    private final BalanceHoldRepository balanceHoldRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final PaymentOutboxRecorder paymentOutboxRecorder;
    private final Clock clock;

    @Transactional
    public void markTimedOut(String paymentReference, String reason) {
        Payment payment = paymentRepository.findByPaymentReference(paymentReference)
            .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                "Payment not found: " + paymentReference));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Skipping timeout for already finalized payment: reference = {}, status = {}",
                paymentReference, payment.getStatus());
            return;
        }

        BalanceHold hold = balanceHoldRepository.findByPaymentReference(paymentReference)
            .orElseThrow(() -> new PaymentException("HOLD_NOT_FOUND",
                "Balance hold not found: " + paymentReference));

        UserBalance senderBalance = userBalanceRepository.findByUserId(payment.getSenderId())
            .orElseThrow(() -> new PaymentException("SENDER_NOT_FOUND",
                "Sender not found: " + payment.getSenderId()));

        senderBalance.releaseHold(hold.getAmount());
        userBalanceRepository.save(senderBalance);

        hold.release(clock);
        balanceHoldRepository.save(hold);

        payment.timeout(reason, clock);
        paymentRepository.save(payment);

        paymentOutboxRecorder.record(payment, PaymentEventType.PAYMENT_TIMEOUT);

        log.warn("Payment timed out, hold released: reference = {}, reason = {}",
            paymentReference, reason);
    }
}
