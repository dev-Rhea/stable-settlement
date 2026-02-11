package com.payment.stablesettlement.infrastructure.kafka;

import com.payment.stablesettlement.domain.outbox.OutboxEvent;
import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import com.payment.stablesettlement.infrastructure.config.KafkaProducerConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OutboxEventPublisher {

    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final String kmsKeyId;

    public OutboxEventPublisher(OutboxEventRepository outboxEventRepository,
                                KafkaTemplate<String, String> kafkaTemplate,
                                Clock clock,
                                @Value("${kms.key-id:}") String kmsKeyId) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.kmsKeyId = kmsKeyId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishEvent(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaProducerConfig.PAYMENT_EVENTS_TOPIC,
                event.getAggregateId(),
                event.getPayload()
        );

        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            record.headers().add("kms-key-id", kmsKeyId.getBytes(StandardCharsets.UTF_8));
        }

        kafkaTemplate.send(record).get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        event.markPublished(clock);
        outboxEventRepository.save(event);

        log.info("Published outbox event: type={}, aggregateId={}",
                event.getEventType(), event.getAggregateId());
    }
}
