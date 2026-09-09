package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.application.exception.DuplicatePaymentException;
import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.hold.BalanceHold;
import com.payment.stablesettlement.domain.hold.BalanceHoldRepository;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentHoldService {

    private final PaymentRepository paymentRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final BalanceHoldRepository balanceHoldRepository;
    private final Clock clock;

    @Transactional
    public Payment createHold(PaymentRequest request) {
        if(paymentRepository.existsByPaymentReference(request.paymentReference())) {
            throw new DuplicatePaymentException(request.paymentReference());
        }

        UserBalance senderBalance = userBalanceRepository.findByUserId(request.senderId())
            .orElseThrow(() -> new PaymentException("SENDER_NOT_FOUND", "sender not found: " + request.senderId()));

        UserBalance recieverBalance = userBalanceRepository.findByUserId(request.receiverId())
            .orElseThrow(() -> new PaymentException("RECEIVER_NOT_FOUND", "Receiver not found: " + request.receiverId()));

        if(!senderBalance.getCurrency().equals(request.currency())) {
            throw new PaymentException("CURRENCY_MISMATCH", "Sender balance currency does not match payment currency");
        }

        if(!recieverBalance.getCurrency().equals(request.currency())) {
            throw new PaymentException("CURRENCY_MISMATCH", "Receiver balance currency does not match payment currency");
        }

        senderBalance.hold(request.amount());
        userBalanceRepository.save(senderBalance);

        try {
            BalanceHold hold = BalanceHold.create(
                request.paymentReference(), request.senderId(), request.amount(),
                request.currency(), clock
            );
            balanceHoldRepository.save(hold);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicatePaymentException(request.paymentReference());
        }

        Payment payment = Payment.builder()
            .paymentReference(request.paymentReference())
            .senderId(request.senderId())
            .receiverId(request.receiverId())
            .amount(request.amount())
            .currency(request.currency())
            .clock(clock)
            .build();
        paymentRepository.save(payment);

        log.info("Payment hold created: reference = {}, amount = {}", payment.getPaymentReference(), payment.getAmount());

        return payment;
    }

    @Transactional
    public Payment attachPgTransaction(String paymentReference, String pgTransactionId) {
        Payment payment = paymentRepository.findByPaymentReference(paymentReference)
            .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND", "Payment not found: " + paymentReference));

        payment.markPgRequested(pgTransactionId, clock);
        paymentRepository.save(payment);

        log.info("PG Transaction attached: reference = {}, pgTransactionId = {}", paymentReference, pgTransactionId);

        return payment;
    }

    @Transactional
    public void releaseOnInitiationFailure(String paymentReference, String reason) {
        Payment payment = paymentRepository.findByPaymentReference(paymentReference)
            .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                "Payment not found: " + paymentReference));

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

        payment.fail(reason, clock);
        paymentRepository.save(payment);

        log.warn("Payment initiation failed, hold released: reference={}, reason={}",
            paymentReference, reason);
    }
}
