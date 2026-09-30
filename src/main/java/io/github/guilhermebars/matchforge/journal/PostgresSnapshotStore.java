package io.github.guilhermebars.matchforge.journal;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class PostgresSnapshotStore implements SnapshotStore {
    private final JdbcClient jdbc;
    private final PersistenceCodec codec;

    public PostgresSnapshotStore(JdbcClient jdbc, PersistenceCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public void save(StoredSnapshot snapshot) {
        snapshot.verify(codec);
        jdbc.sql("""
                INSERT INTO snapshot(seq,payload,created_at) VALUES (:seq, CAST(:payload AS jsonb), CURRENT_TIMESTAMP)
                ON CONFLICT (seq) DO UPDATE SET payload = EXCLUDED.payload, created_at = EXCLUDED.created_at
                """)
                .param("seq", snapshot.seq())
                .param("payload", codec.encode(snapshot))
                .update();
    }

    @Override
    public Optional<StoredSnapshot> latest() {
        return jdbc.sql("SELECT seq,payload FROM snapshot ORDER BY seq DESC LIMIT 1")
                .query((rs, row) -> {
                    var snapshot = codec.decode(rs.getString("payload"), StoredSnapshot.class);
                    snapshot.verify(codec);
                    if (snapshot.seq() != rs.getLong("seq"))
                        throw new IllegalStateException("Snapshot row sequence mismatch");
                    return snapshot;
                })
                .optional();
    }
}
