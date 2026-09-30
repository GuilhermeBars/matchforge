package io.github.guilhermebars.matchforge.journal;

import java.util.List;

/** Write-ahead commands, contiguous from 1. readFrom is inclusive; readAfter is exclusive. */
public interface Journal extends AutoCloseable {
    long append(JournalEntry entry);
    List<JournalEntry> readAfter(long sequenceExclusive);
    long lastSequence();
    default List<JournalEntry> readBatchAfter(long seq, int limit) {
        if (limit < 1) throw new IllegalArgumentException("positive batch size required");
        return readAfter(seq).stream().limit(limit).toList();
    }
    default List<JournalEntry> readFrom(long seq) {
        if (seq < 1) throw new IllegalArgumentException("seq must be positive");
        return readAfter(seq - 1);
    }
    default long lastSeq() { return lastSequence(); }
    @Override default void close() {}
}

