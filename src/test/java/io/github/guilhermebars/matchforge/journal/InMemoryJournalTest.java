package io.github.guilhermebars.matchforge.journal;

import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class InMemoryJournalTest {
    private JournalEntry entry(long sequence) {
        return new JournalEntry(sequence, "command.v1", "{}", Instant.EPOCH);
    }

    @Test void appendsContiguouslyAndReturnsImmutableDetachedReads() {
        var journal = new InMemoryJournal();
        assertThat(journal.lastSequence()).isZero();
        journal.append(entry(1));
        var before = journal.readAfter(0);
        journal.append(entry(2));
        assertThat(before).containsExactly(entry(1));
        assertThatThrownBy(() -> before.add(entry(3))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(journal.readAfter(1)).containsExactly(entry(2));
        assertThat(journal.readAfter(2)).isEmpty();
        assertThat(journal.lastSequence()).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> journal.append(entry(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> journal.append(entry(4)));
        assertThatIllegalArgumentException().isThrownBy(() -> journal.readAfter(-1));
        assertThat(journal.lastSequence()).isEqualTo(2);
    }
}
