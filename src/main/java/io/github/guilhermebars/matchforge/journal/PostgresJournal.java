package io.github.guilhermebars.matchforge.journal;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One session advisory lock fences competing writer instances, including during recovery. */
public final class PostgresJournal implements Journal {
    private final JdbcClient jdbc;
    private final Connection lease;
    private boolean closed;

    public PostgresJournal(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(748392106)")) {
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) throw new IllegalStateException("Another journal writer is active");
                }
            }
            lease = connection;
        } catch (Exception e) {
            if (connection != null)
                try {
                    connection.close();
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            throw new IllegalStateException("Cannot acquire journal writer lease", e);
        }
    }

    @Override
    public synchronized long append(JournalEntry entry) {
        if (closed) throw new IllegalStateException("Journal closed");
        // Use the lease connection itself: losing the lock connection can never leave an unfenced writer.
        var writer = JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(lease, true));
        int inserted = writer.sql("""
                INSERT INTO journal_entry(seq, type, payload, created_at)
                SELECT :seq, :type, CAST(:payload AS jsonb), :created
                WHERE :seq = (SELECT COALESCE(MAX(seq), 0) + 1 FROM journal_entry)
                """)
                .param("seq", entry.seq())
                .param("type", entry.type())
                .param("payload", entry.payload())
                .param("created", Timestamp.from(entry.createdAt()))
                .update();
        if (inserted != 1) throw new IllegalArgumentException("journal sequence must be contiguous");
        return entry.seq();
    }

    @Override
    public List<JournalEntry> readAfter(long seq) {
        if (seq < 0) throw new IllegalArgumentException("negative sequence");
        return jdbc.sql("SELECT seq,type,payload,created_at FROM journal_entry WHERE seq > :seq ORDER BY seq")
                .param("seq", seq)
                .query((rs, row) -> new JournalEntry(
                        rs.getLong("seq"),
                        rs.getString("type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public List<JournalEntry> readBatchAfter(long seq, int limit) {
        if (seq < 0 || limit < 1) throw new IllegalArgumentException("invalid batch bounds");
        return jdbc.sql(
                        "SELECT seq,type,payload,created_at FROM journal_entry WHERE seq > :seq ORDER BY seq LIMIT :limit")
                .param("seq", seq)
                .param("limit", limit)
                .query((rs, row) -> new JournalEntry(
                        rs.getLong("seq"),
                        rs.getString("type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public long lastSequence() {
        return jdbc.sql("SELECT COALESCE(MAX(seq),0) FROM journal_entry")
                .query(Long.class)
                .single();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            try (var statement = lease.prepareStatement("SELECT pg_advisory_unlock(748392106)")) {
                statement.execute();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot release writer lease", e);
        } finally {
            try {
                lease.close();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
