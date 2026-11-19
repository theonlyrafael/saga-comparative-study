ALTER TABLE outbox_messages ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
DROP INDEX ix_outbox_pending;
CREATE INDEX ix_outbox_ready ON outbox_messages (next_attempt_at, created_at, message_id)
    WHERE published_at IS NULL;
