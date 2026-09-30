package io.github.guilhermebars.matchforge.journal;
import java.util.Optional;
public final class InMemorySnapshotStore implements SnapshotStore {
    private StoredSnapshot latest;
    @Override public synchronized void save(StoredSnapshot snapshot) {
        snapshot.verify(new PersistenceCodec());
        if (latest == null || snapshot.seq() >= latest.seq()) latest = snapshot;
    }
    @Override public synchronized Optional<StoredSnapshot> latest() { return Optional.ofNullable(latest); }
}
