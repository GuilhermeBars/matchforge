package io.github.guilhermebars.matchforge.events;
import io.github.guilhermebars.matchforge.engine.DomainEvent;
import java.util.List;
public interface EventPublisher extends AutoCloseable {
    void publish(List<DomainEvent> events);
    default void publish(io.github.guilhermebars.matchforge.engine.CommandEnvelope command, List<DomainEvent> events) { publish(events); }
    @Override default void close() {}
}

