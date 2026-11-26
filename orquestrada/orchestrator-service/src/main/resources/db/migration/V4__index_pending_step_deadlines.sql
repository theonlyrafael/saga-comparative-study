ALTER TABLE saga_steps
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    ADD COLUMN wait_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Preserve elapsed time on upgrade. Completed steps never enter the deadline index.
UPDATE saga_steps SET wait_started_at = issued_at WHERE completed_at IS NULL;
CREATE INDEX ix_saga_steps_waiting ON saga_steps (wait_started_at, operation_id)
    WHERE completed_at IS NULL;
