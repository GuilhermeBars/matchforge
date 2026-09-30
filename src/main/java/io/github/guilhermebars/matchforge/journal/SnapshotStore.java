package io.github.guilhermebars.matchforge.journal;

import java.util.Optional;

public interface SnapshotStore {
    void save(StoredSnapshot snapshot);

    Optional<StoredSnapshot> latest();
}
