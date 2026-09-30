package io.github.guilhermebars.matchforge.journal;

import java.util.List;

/** Append succeeds before processing; entries start at 1 and are contiguous. */
public interface Journal {
    void append(JournalEntry entry);
    List<JournalEntry> readAfter(long sequenceExclusive);
    long lastSequence();
}
