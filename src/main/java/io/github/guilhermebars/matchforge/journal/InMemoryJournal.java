package io.github.guilhermebars.matchforge.journal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Volatile local/test adapter. No persistence across process restarts. */
public final class InMemoryJournal implements Journal {
    private final List<JournalEntry> entries = new ArrayList<>();

    @Override
    public synchronized long append(JournalEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.seq() != Math.addExact(lastSequence(), 1)) {
            throw new IllegalArgumentException("journal sequence must be contiguous");
        }
        entries.add(entry);
        return entry.seq();
    }

    @Override
    public synchronized List<JournalEntry> readAfter(long sequenceExclusive) {
        if (sequenceExclusive < 0) throw new IllegalArgumentException("sequence must be non-negative");
        return entries.stream().filter(entry -> entry.seq() > sequenceExclusive).toList();
    }

    @Override
    public synchronized long lastSequence() {
        return entries.isEmpty() ? 0 : entries.getLast().seq();
    }
}
