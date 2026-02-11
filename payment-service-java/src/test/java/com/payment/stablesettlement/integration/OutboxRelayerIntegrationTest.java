package com.payment.stablesettlement.integration;

import com.payment.stablesettlement.domain.outbox.OutboxEvent;
import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import com.payment.stablesettlement.infrastructure.config.KafkaProducerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
class OutboxRelayerIntegrationTest extends TestContainerConfig {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("Outbox 이벤트가 Kafka로 발행되고 published=true로 마킹된다")
    void relayOutboxEventToKafka() {
        OutboxEvent event = new OutboxEvent(
                "Payment", "PAY-OUTBOX-001", "PAYMENT_COMPLETED",
                "{\"paymentReference\":\"PAY-OUTBOX-001\",\"status\":\"COMPLETED\"}",
                Clock.systemDefaultZone());
        outboxEventRepository.save(event);

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    OutboxEvent updated = outboxEventRepository.findById(event.getId()).orElseThrow();
                    assertThat(updated.isPublished()).isTrue();
                    assertThat(updated.getPublishedAt()).isNotNull();
                });

        try (KafkaConsumer<String, String> consumer = createConsumer()) {
            consumer.subscribe(List.of(KafkaProducerConfig.PAYMENT_EVENTS_TOPIC));

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records.count()).isGreaterThan(0);

            boolean found = false;
            for (var record : records) {
                if (record.value().contains("PAY-OUTBOX-001")) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }

    private KafkaConsumer<String, String> createConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + System.currentTimeMillis(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ));
    }
}
