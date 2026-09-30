-- The ledger is derived from the command journal and included in engine snapshots.
-- Remove the unused session-1 projection rather than imply PostgreSQL dual writes.
DROP TABLE ledger_entry;
