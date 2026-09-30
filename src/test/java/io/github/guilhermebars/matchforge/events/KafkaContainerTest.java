package io.github.guilhermebars.matchforge.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.guilhermebars.matchforge.domain.AccountId;
import io.github.guilhermebars.matchforge.engine.DomainEvent;
import io.github.guilhermebars.matchforge.journal.PersistenceCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class KafkaContainerTest {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    @Test
    void publishesVersionedEventToBroker() {
        var properties = Map.<String, Object>of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "key.serializer",
                StringSerializer.class,
                "value.serializer",
                StringSerializer.class,
                "acks",
                "all",
                "enable.idempotence",
                true);
        var factory = new DefaultKafkaProducerFactory<String, String>(properties);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "group.id",
                "test",
                "auto.offset.reset",
                "earliest",
                "key.deserializer",
                StringDeserializer.class,
                "value.deserializer",
                StringDeserializer.class))) {
            var template = new KafkaTemplate<>(factory);
            new KafkaEventPublisher(template, new PersistenceCodec())
                    .publish(List.of(new DomainEvent.AccountCreated(
                            new DomainEvent.Header(1, Instant.parse("2026-01-01T00:00:00Z")), new AccountId("alice"))));
            consumer.subscribe(List.of("matchforge.events"));
            ConsumerRecords<String, String> records = ConsumerRecords.empty();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (records.isEmpty() && System.nanoTime() < deadline) records = consumer.poll(Duration.ofMillis(500));
            assertThat(records.count()).isEqualTo(1);
            var record = records.iterator().next();
            assertThat(record.key()).isEqualTo("alice");
            assertThat(record.value()).contains("\"version\":1", "\"eventType\":\"account-created\"", "alice");
            template.destroy();
        } finally {
            factory.destroy();
        }
    }
}
