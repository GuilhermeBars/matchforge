package io.github.guilhermebars.matchforge.config;

import io.github.guilhermebars.matchforge.events.EventPublisher;
import io.github.guilhermebars.matchforge.events.InProcessEventPublisher;
import io.github.guilhermebars.matchforge.events.KafkaEventPublisher;
import io.github.guilhermebars.matchforge.journal.Journal;
import io.github.guilhermebars.matchforge.journal.PersistenceCodec;
import io.github.guilhermebars.matchforge.journal.SnapshotStore;
import io.github.guilhermebars.matchforge.service.EngineService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
public class EngineConfiguration {
    @Bean
    InProcessEventPublisher inProcessEventPublisher() {
        return new InProcessEventPublisher();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "matchforge.events.publisher", havingValue = "kafka")
    static class KafkaConfiguration {
        @Bean
        DefaultKafkaProducerFactory<String, String> eventProducerFactory(
                @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String servers) {
            return new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    servers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    StringSerializer.class,
                    ProducerConfig.ACKS_CONFIG,
                    "all",
                    ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                    true,
                    ProducerConfig.MAX_BLOCK_MS_CONFIG,
                    5000,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                    30000,
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                    10000));
        }

        @Bean
        KafkaTemplate<String, String> eventKafkaTemplate(DefaultKafkaProducerFactory<String, String> factory) {
            return new KafkaTemplate<>(factory);
        }

        @Bean
        KafkaEventPublisher kafkaEventPublisher(KafkaTemplate<String, String> template, PersistenceCodec codec) {
            return new KafkaEventPublisher(template, codec);
        }

        @Bean
        @Primary
        EventPublisher combinedPublisher(InProcessEventPublisher local, KafkaEventPublisher kafka) {
            return new EventPublisher() {
                @Override
                public void publish(java.util.List<io.github.guilhermebars.matchforge.engine.DomainEvent> events) {
                    local.publish(events);
                    kafka.publish(events);
                }

                @Override
                public void publish(
                        io.github.guilhermebars.matchforge.engine.CommandEnvelope command,
                        java.util.List<io.github.guilhermebars.matchforge.engine.DomainEvent> events) {
                    local.publish(events);
                    kafka.publish(command, events);
                }
            };
        }
    }

    @Bean
    EngineService engineService(
            MatchforgeProperties properties,
            Journal journal,
            SnapshotStore snapshots,
            PersistenceCodec codec,
            EventPublisher publisher,
            MeterRegistry metrics,
            @Value("${matchforge.engine.queue-capacity:1024}") int capacity) {
        return new EngineService(
                properties.instruments().stream()
                        .map(MatchforgeProperties.Instrument::toDomain)
                        .toList(),
                journal,
                snapshots,
                codec,
                publisher,
                metrics,
                properties.snapshot().interval(),
                capacity,
                Clock.systemUTC());
    }
}
