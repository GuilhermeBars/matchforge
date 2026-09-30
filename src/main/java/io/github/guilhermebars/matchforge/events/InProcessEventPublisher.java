package io.github.guilhermebars.matchforge.events;

import io.github.guilhermebars.matchforge.engine.DomainEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Listeners execute in order on the writer; they must enqueue slow work themselves. */
public final class InProcessEventPublisher implements EventPublisher {
    private final CopyOnWriteArrayList<Consumer<List<DomainEvent>>> listeners = new CopyOnWriteArrayList<>();

    public AutoCloseable register(Consumer<List<DomainEvent>> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void publish(List<DomainEvent> events) {
        var batch = List.copyOf(events);
        RuntimeException failure = null;
        for (var listener : listeners) {
            try {
                listener.accept(batch);
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        listeners.clear();
    }
}
