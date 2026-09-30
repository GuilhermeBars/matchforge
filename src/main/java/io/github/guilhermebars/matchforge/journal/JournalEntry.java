package io.github.guilhermebars.matchforge.journal;

import java.time.Instant;
import java.util.Objects;

/** Immutable envelope; payload is versioned JSON supplied by the command codec. */
public record JournalEntry(long seq, String type, String payload, Instant createdAt) {
    public JournalEntry {
        if (seq <= 0) throw new IllegalArgumentException("seq must be positive");
        if (Objects.requireNonNull(type, "type").isBlank()) throw new IllegalArgumentException("type is blank");
        if (Objects.requireNonNull(payload, "payload").isBlank())
            throw new IllegalArgumentException("payload is blank");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
