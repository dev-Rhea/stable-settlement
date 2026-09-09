package com.payment.reconciliation.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.reconciliation.domain.ReconciliationResult;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "reconciliation.notify.kafka-enabled",
    havingValue = "true", matchIfMissing = true)
public class KafkaMismatchNotifier implements MismatchNotifier {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaMismatchNotifier(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${reconciliation.notify.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void notifyMismatches(List<ReconciliationResult> mismatches) {
        for (ReconciliationResult mismatch : mismatches) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                topic, mismatch.paymentReference(), toPayload(mismatch));
            record.headers().add("event-type", EVENT_TYPE.getBytes(StandardCharsets.UTF_8));

            try {
                kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while publishing mismatch", e);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Failed to publish mismatch: " + mismatch.paymentReference(), e);
            }
        }

        log.info("Published {} reconciliation mismatches to {}", mismatches.size(), topic);
    }

    private String toPayload(ReconciliationResult mismatch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", EVENT_TYPE);
        payload.put("reconciliationDate", mismatch.reconciliationDate().toString());
        payload.put("paymentReference", mismatch.paymentReference());
        payload.put("status", mismatch.status().name());
        payload.put("internalAmount", mismatch.internalAmount());
        payload.put("externalAmount", mismatch.externalAmount());
        payload.put("currency", mismatch.currency());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize mismatch payload", e);
        }
    }
}
