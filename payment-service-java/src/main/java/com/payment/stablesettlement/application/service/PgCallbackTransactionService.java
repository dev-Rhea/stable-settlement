package com.payment.stablesettlement.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.hold.BalanceHold;
import com.payment.stablesettlement.domain.hold.BalanceHoldRepository;
import com.payment.stablesettlement.domain.outbox.OutboxEvent;
import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import com.payment.stablesettlement.interfaces.dto.pg.PgCallbackRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PgCallbackTransactionService {

    private final PaymentRepository paymentRepository;
    private final BalanceHoldRepository balanceHoldRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final TransactionSigner transactionSigner;

    @Transactional
    public void finalizeCallback(PgCallbackRequest request) {
        Timer.Sample timerSample = Timer.start(meterRegistry);

        Payment payment = paymentRepository.findByPgTransactionId(request.pgTransactionId())
            .orElseThrow(() -> new PaymentException("PG_CALLBACK_PAYMENT_NOT_FOUND", "No payment found for pgTransactionId: " + request.pgTransactionId()));

        if(payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Ignoring duplicate/late PG callback: reference = {}, status = {}", payment.getPaymentReference(), payment.getStatus());

            meterRegistry.counter("payment.pg_callback.processed", "status", "duplicate").increment();
            timerSample.stop(meterRegistry.timer("payment.pg_callback.duration", "status", "duplicate"));
            return;
        }

        BalanceHold hold = balanceHoldRepository.findByPaymentReference(payment.getPaymentReference())
            .orElseThrow(() -> new PaymentException("HOLD_NOT_FOUND", "Balance hold not found: " + payment.getPaymentReference()));

        try {
            Payment result = switch (request.result()) {
                case APPROVED -> approve(payment, hold);
                case REJECTED -> reject(payment, hold, request.reason());
            };

            paymentRepository.save(result);

            meterRegistry.counter("payment.pg_callback.processed", "status", "success").increment();
            timerSample.stop(meterRegistry.timer("payment.pg_callback.duration", "status", "success"));
        } catch (Exception e) {
            meterRegistry.counter("payment.pg_callback.processed", "status", "failure").increment();
            timerSample.stop(meterRegistry.timer("payment.pg_callback.duration", "status", "failure"));
            throw e;
        }
    }

    private Payment approve(Payment payment, BalanceHold hold) {
        UserBalance senderBalance = userBalanceRepository.findByUserId(payment.getSenderId())
            .orElseThrow(() -> new PaymentException("SENDER_NOT_FOUND", "Sender not found" + payment.getSenderId()));

        UserBalance receiverBalance = userBalanceRepository.findByUserId(payment.getReceiverId())
            .orElseThrow(() -> new PaymentException("RECEIVER_NOT_FOUND", "Receiver not found" + payment.getReceiverId()));

        senderBalance.captureHold(hold.getAmount());
        receiverBalance.credit(hold.getAmount());
        userBalanceRepository.save(senderBalance);
        userBalanceRepository.save(receiverBalance);

        hold.capture(clock);
        balanceHoldRepository.save(hold);

        payment.complete(clock);

        String signature = transactionSigner.signTransaction(payment);
        payment.attachSignature(signature);

        paymentRepository.save(payment);
        saveOutboxEvent(payment, "PAYMENT_COMPLETED");

        log.info("Payment approved: reference = {}, amount = {}", payment.getPaymentReference(), payment.getAmount());

        return payment;
    }

    private Payment reject(Payment payment, BalanceHold hold, String reason) {
        UserBalance senderBalance = userBalanceRepository.findByUserId(payment.getSenderId())
            .orElseThrow(() -> new PaymentException("SENDER_NOT_FOUND",
                "Sender not found" + payment.getSenderId()));

        senderBalance.releaseHold(hold.getAmount());
        userBalanceRepository.save(senderBalance);

        hold.release(clock);
        balanceHoldRepository.save(hold);

        payment.fail(reason, clock);
        paymentRepository.save(payment);

        saveOutboxEvent(payment, "PAYMENT_FAILED");

        log.info("Payment rejected: reference = {}, reason = {}", payment.getPaymentReference(),
            reason);

        return payment;
    }

    private void saveOutboxEvent(Payment payment, String eventType) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "paymentReference", payment.getPaymentReference(),
                "senderId", payment.getSenderId(),
                "receiverId", payment.getReceiverId(),
                "amount", payment.getAmount(),
                "currency", payment.getCurrency(),
                "status", payment.getStatus(),
                "transactionSignature", payment.getTransactionSignature() != null ? payment.getTransactionSignature() : ""
            ));

            OutboxEvent event = new OutboxEvent(
                "Payment",
                payment.getPaymentReference(),
                eventType,
                payload,
                clock
            );
            outboxEventRepository.save(event);
        } catch (JsonProcessingException e) {
            throw new PaymentException("SERIALIZATION_ERROR", "Failed to serialize payment event", e);
        }
    }

}
