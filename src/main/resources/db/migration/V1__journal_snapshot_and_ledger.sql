-- Sequence allocation belongs to the single writer, never a DB identity.
CREATE TABLE journal_entry (
    seq BIGINT PRIMARY KEY CHECK (seq > 0),
    type VARCHAR(128) NOT NULL CHECK (length(type) > 0),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- seq is the last fully applied command boundary covered by the snapshot.
CREATE TABLE snapshot (
    seq BIGINT PRIMARY KEY CHECK (seq >= 0),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- Rebuildable projections. A trade has >= 2 signed postings per asset.
-- Balancing across rows is checked by LedgerService before atomic persistence.
CREATE TABLE ledger_entry (
    journal_seq BIGINT NOT NULL REFERENCES journal_entry(seq),
    posting_index INTEGER NOT NULL CHECK (posting_index >= 0),
    transaction_id VARCHAR(128) NOT NULL,
    trade_id VARCHAR(128),
    account_id VARCHAR(128) NOT NULL,
    asset VARCHAR(12) NOT NULL,
    amount_units BIGINT NOT NULL CHECK (amount_units <> 0),
    amount_scale SMALLINT NOT NULL CHECK (amount_scale BETWEEN 0 AND 18),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (journal_seq, posting_index)
);
CREATE INDEX ledger_entry_account_sequence ON ledger_entry(account_id, journal_seq, posting_index);
CREATE INDEX ledger_entry_transaction ON ledger_entry(transaction_id);
