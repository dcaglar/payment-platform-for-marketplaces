-- CENTRAL DB SCHEMA

DROP TABLE IF EXISTS outbox_event CASCADE;
CREATE TABLE outbox_event (
  oeid        BIGINT       NOT NULL,
  event_type  VARCHAR(255) NOT NULL,
  aggregate_id VARCHAR(255) NOT NULL,
  payload     TEXT         NOT NULL,
  status      VARCHAR(50)  NOT NULL,
  created_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
  updated_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
  claimed_at  TIMESTAMP WITHOUT TIME ZONE NULL,
  claimed_by  VARCHAR(128) NULL,
  PRIMARY KEY (oeid, created_at)
);
CREATE INDEX IF NOT EXISTS idx_outbox_event_createdat ON outbox_event (created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_event_status    ON outbox_event (status);
CREATE INDEX IF NOT EXISTS idx_outbox_status_createdat ON outbox_event (status, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_status_claimed_at ON outbox_event (status, claimed_at);

DROP TABLE IF EXISTS edge_watermarks CASCADE;
CREATE TABLE edge_watermarks (
    edge_node_id VARCHAR(64) PRIMARY KEY,
    forwarded_up_to TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

DROP TABLE IF EXISTS payments CASCADE;
CREATE TABLE payments (
    payment_id BIGINT PRIMARY KEY,
    payment_intent_id BIGINT NOT NULL,
    buyer_id VARCHAR(255) NOT NULL,
    merchant_account VARCHAR(255) NOT NULL,
    processing_model VARCHAR(32) NOT NULL,
    total_amount_value BIGINT NOT NULL,
    captured_amount_value BIGINT NOT NULL DEFAULT 0,
    refunded_amount_value BIGINT NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL,
    splits_json JSONB NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT chk_payments_status_valid CHECK (status IN ('AUTHORIZED', 'SENT_FOR_SETTLE', 'CAPTURED', 'PARTIALLY_CAPTURED', 'VOIDED', 'PARTIALLY_REFUNDED','SETTLED' 'REFUNDED')),
    CONSTRAINT chk_payments_processing_model CHECK (processing_model IN ('DIRECT_MERCHANT', 'MARKETPLACE')),
    CONSTRAINT chk_payments_currency_3 CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_payments_total_positive CHECK (total_amount_value > 0),
    CONSTRAINT chk_payments_captured_le_total CHECK (captured_amount_value >= 0 AND captured_amount_value <= total_amount_value),
    CONSTRAINT chk_payments_refunded_le_captured CHECK (refunded_amount_value >= 0 AND refunded_amount_value <= captured_amount_value)
);
CREATE INDEX IF NOT EXISTS idx_payments_payment_intent_id ON payments(payment_intent_id);
CREATE INDEX IF NOT EXISTS idx_payments_merchant_account ON payments(merchant_account);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);

DROP TABLE IF EXISTS payment_tx CASCADE;
CREATE TABLE payment_tx (
    tx_id BIGINT PRIMARY KEY,
    tx_type VARCHAR(32) NOT NULL,
    parent_tx_id BIGINT,
    payment_id BIGINT NOT NULL,
    payment_intent_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    settle_status VARCHAR(16),
    acquirer_batch_ref VARCHAR(255),
    settled_amount_value BIGINT,
    acquirer_reference VARCHAR(255),
    amount_value BIGINT NOT NULL,
    amount_currency CHAR(3) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT fk_payment_tx_parent FOREIGN KEY (parent_tx_id) REFERENCES payment_tx(tx_id),
    CONSTRAINT chk_tx_type CHECK (tx_type IN ('AUTHORIZATION', 'CAPTURE', 'REFUND', 'SETTLE', 'INTERNAL_TRANSFER', 'COMMISSION_FEE')),
    CONSTRAINT chk_tx_amount_positive CHECK (amount_value > 0),
    CONSTRAINT chk_tx_currency CHECK (amount_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_tx_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT chk_tx_settle_status CHECK (settle_status IS NULL OR settle_status IN ('UNMATCHED', 'MATCHED', 'DISCREPANCY'))
);
CREATE INDEX IF NOT EXISTS idx_payment_tx_payment_id ON payment_tx(payment_id);

DROP TABLE IF EXISTS journal_entries CASCADE;
CREATE TABLE journal_entries (
    id VARCHAR(128) PRIMARY KEY,
    journal_type VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    payment_id BIGINT NOT NULL,
    payment_intent_id BIGINT,
    tx_id BIGINT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT fk_journal_entries_tx_id FOREIGN KEY (tx_id) REFERENCES payment_tx(tx_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_journal_entries_payment_id ON journal_entries(payment_id);

DROP TABLE IF EXISTS postings CASCADE;
CREATE TABLE postings (
    id BIGSERIAL PRIMARY KEY,
    journal_id VARCHAR(128) NOT NULL,
    account_code VARCHAR(128) NOT NULL,
    account_type VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    direction VARCHAR(8) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    CONSTRAINT fk_postings_journal FOREIGN KEY (journal_id) REFERENCES journal_entries(id) ON DELETE CASCADE,
    CONSTRAINT uq_postings_journal_account UNIQUE (journal_id, account_code)
);
CREATE INDEX IF NOT EXISTS idx_postings_account_code ON postings(account_code);

DROP TABLE IF EXISTS account_balances CASCADE;
CREATE TABLE account_balances (
    account_code VARCHAR(128) PRIMARY KEY,
    balance BIGINT NOT NULL,
    last_applied_entry_id BIGINT NOT NULL DEFAULT 0,
    last_snapshot_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);

DROP TABLE IF EXISTS account_directory CASCADE;
CREATE TABLE account_directory (
    account_code VARCHAR(128) PRIMARY KEY,
    account_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    category VARCHAR(32),
    country VARCHAR(2),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
);
CREATE INDEX IF NOT EXISTS idx_account_directory_entity ON account_directory(entity_id);
