package io.github.guilhermebars.matchforge.events;

import io.github.guilhermebars.matchforge.domain.*;
import io.github.guilhermebars.matchforge.engine.*;
import io.github.guilhermebars.matchforge.journal.PersistenceCodec;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EventPublisherTest {
    @Test void listenersCanUnregisterAndOneFailureDoesNotSuppressOtherListeners() throws Exception {
        var publisher = new InProcessEventPublisher(); var received = new ArrayList<List<DomainEvent>>();
        var subscription = publisher.register(events -> { throw new IllegalStateException("listener failure"); });
        publisher.register(received::add);
        var batch = List.<DomainEvent>of(new DomainEvent.AccountCreated(new DomainEvent.Header(1, Instant.EPOCH), new AccountId("a")));
        assertThatThrownBy(() -> publisher.publish(batch)).hasMessage("listener failure");
        assertThat(received).containsExactly(batch);
        subscription.close(); publisher.publish(batch); assertThat(received).hasSize(2);
        publisher.close(); publisher.publish(batch); assertThat(received).hasSize(2);
    }
    @SuppressWarnings("unchecked")
    @Test void kafkaUsesStableJsonAndCommandAccountForCancelRouting() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var publisher = new KafkaEventPublisher(kafka, new PersistenceCodec());
        var account = new AccountId("alice"); var order = new OrderId("1");
        var envelope = new CommandEnvelope(1, Instant.EPOCH, new Command.CancelOrder(account, order));
        publisher.publish(envelope, List.of(new DomainEvent.OrderCancelled(new DomainEvent.Header(1, Instant.EPOCH), order, 2, DomainEvent.CancelReason.USER)));
        verify(kafka).send(eq("matchforge.events"), eq("alice"), argThat(json -> json.contains("\"eventType\":\"order-cancelled\"") && json.contains("\"sequence\":1")));
    }
    @SuppressWarnings("unchecked")
    @Test void kafkaBrokerFailurePropagates() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        var publisher = new KafkaEventPublisher(kafka, new PersistenceCodec());
        assertThatThrownBy(() -> publisher.publish(List.of(new DomainEvent.AccountCreated(new DomainEvent.Header(1, Instant.EPOCH), new AccountId("a")))))
                .hasMessage("Kafka publication failed");
    }
}
