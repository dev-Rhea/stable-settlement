package com.payment.stablesettlement.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.outbox.OutboxEvent;
import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import com.payment.stablesettlement.domain.payment.Payment;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제 상태 변화를 Outbox 이벤트로 기록한다. 호출자의 트랜잭션에 참여하므로
 * 결제 상태 변경과 이벤트 저장이 하나의 커밋 단위로 묶인다.
 */
@Component
@RequiredArgsConstructor
public class PaymentOutboxRecorder {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public void record(Payment payment, PaymentEventType eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentReference", payment.getPaymentReference());
        payload.put("senderId", payment.getSenderId());
        payload.put("receiverId", payment.getReceiverId());
        payload.put("amount", payment.getAmount());
        payload.put("currency", payment.getCurrency());
        payload.put("status", payment.getStatus());
        payload.put("pgTransactionId", payment.getPgTransactionId() != null
            ? payment.getPgTransactionId() : "");
        payload.put("failureReason", payment.getFailureReason() != null
            ? payment.getFailureReason() : "");
        payload.put("transactionSignature", payment.getTransactionSignature() != null
            ? payment.getTransactionSignature() : "");

        try {
            OutboxEvent event = new OutboxEvent(
                "Payment",
                payment.getPaymentReference(),
                eventType.name(),
                objectMapper.writeValueAsString(payload),
                clock
            );
            outboxEventRepository.save(event);
        } catch (JsonProcessingException e) {
            throw new PaymentException("SERIALIZATION_ERROR", "Failed to serialize payment event", e);
        }
    }
}
