package io.github.guilhermebars.matchforge.engine;

import java.time.Instant;
import java.util.Objects;

public record CommandEnvelope(long sequence, Instant timestamp, Command command) {
    public CommandEnvelope {
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(command);
    }
}
