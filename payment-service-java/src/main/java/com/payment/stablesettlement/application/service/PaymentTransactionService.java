package com.payment.stablesettlement.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.stablesettlement.application.exception.DuplicatePaymentException;
import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.outbox.OutboxEvent;
import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final TransactionSigner transactionSigner;

    @Transactional
    public Payment executePayment(PaymentRequest request) {
        Timer.Sample timerSample = Timer.start(meterRegistry);

        try {
            Payment payment = doExecutePayment(request);

            meterRegistry.counter("payment.processed", "status", "success").increment();
            timerSample.stop(meterRegistry.timer("payment.duration", "status", "success"));

            return payment;
        } catch (Exception e) {
            meterRegistry.counter("payment.processed", "status", "failure").increment();
            timerSample.stop(meterRegistry.timer("payment.duration", "status", "failure"));
            throw e;
        }
    }

    private Payment doExecutePayment(PaymentRequest request) {
        if (paymentRepository.existsByPaymentReference(request.paymentReference())) {
            throw new DuplicatePaymentException(request.paymentReference());
        }

        UserBalance senderBalance = userBalanceRepository.findByUserId(request.senderId())
                .orElseThrow(() -> new PaymentException("SENDER_NOT_FOUND",
                        "Sender not found: " + request.senderId()));

        UserBalance receiverBalance = userBalanceRepository.findByUserId(request.receiverId())
                .orElseThrow(() -> new PaymentException("RECEIVER_NOT_FOUND",
                        "Receiver not found: " + request.receiverId()));

        if (!senderBalance.getCurrency().equals(request.currency())) {
            throw new PaymentException("CURRENCY_MISMATCH",
                    "Sender balance currency does not match payment currency");
        }
        if (!receiverBalance.getCurrency().equals(request.currency())) {
            throw new PaymentException("CURRENCY_MISMATCH",
                    "Receiver balance currency does not match payment currency");
        }

        senderBalance.deduct(request.amount());
        receiverBalance.credit(request.amount());

        userBalanceRepository.save(senderBalance);
        userBalanceRepository.save(receiverBalance);

        Payment payment = Payment.builder()
                .paymentReference(request.paymentReference())
                .senderId(request.senderId())
                .receiverId(request.receiverId())
                .amount(request.amount())
                .currency(request.currency())
                .clock(clock)
                .build();
        payment.complete(clock);

        String signature = transactionSigner.signTransaction(payment);
        payment.attachSignature(signature);

        paymentRepository.save(payment);

        saveOutboxEvent(payment);

        log.info("Payment completed: reference={}, amount={}",
                payment.getPaymentReference(), payment.getAmount());

        return payment;
    }

    private void saveOutboxEvent(Payment payment) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "paymentReference", payment.getPaymentReference(),
                    "senderId", payment.getSenderId(),
                    "receiverId", payment.getReceiverId(),
                    "amount", payment.getAmount(),
                    "currency", payment.getCurrency(),
                    "status", payment.getStatus().name(),
                    "transactionSignature", payment.getTransactionSignature() != null
                            ? payment.getTransactionSignature() : ""
            ));

            OutboxEvent event = new OutboxEvent(
                    "Payment",
                    payment.getPaymentReference(),
                    "PAYMENT_COMPLETED",
                    payload,
                    clock
            );
            outboxEventRepository.save(event);
        } catch (JsonProcessingException e) {
            throw new PaymentException("SERIALIZATION_ERROR",
                    "Failed to serialize payment event", e);
        }
    }
}
