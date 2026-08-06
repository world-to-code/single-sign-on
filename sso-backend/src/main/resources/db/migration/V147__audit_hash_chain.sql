-- Tamper-EVIDENCE for the audit trail.
--
-- audit_event carried no integrity mechanism: anyone with database write could delete a row or flip `success`,
-- and a reader could not tell "no event" from "event deleted". When an IdP is compromised the audit trail is
-- the first thing edited, so silence there is exactly what must stop being indistinguishable from innocence.
--
-- Each sealed row commits to its own content and to the row before it, so a modification breaks that row's
-- digest and a deletion leaves a hole in `seq`. Both become visible to a walk of the chain.
--
-- WHAT THIS DOES NOT DO: an attacker who can write to this table can also RECOMPUTE the whole chain. Detecting
-- that requires publishing the head where the operator cannot rewrite it, which is deliberately a later step
-- (see docs/trust-signals-and-response-design.md §1b) — it is a different threat model, not a missing piece of
-- this one.
--
-- Columns are nullable because sealing is ASYNCHRONOUS. Writes stay on the fast path (audit must never become
-- a bottleneck on authentication), a sweeper seals afterwards, and rows written before this migration are
-- simply never sealed. So "unsealed" is a normal, expected state and not a defect to alert on.

ALTER TABLE audit_event
    -- Position in the chain. Assigned at SEAL time, not insert time: `id` is allocated before COMMIT, so it
    -- does not order rows by when they became visible, and a chain built on it would show phantom gaps.
    ADD COLUMN seq           BIGINT,
    -- Per-row salt, so a later erasure can drop one row's ability to be recomputed (crypto-shredding) without
    -- breaking the links on either side of it. The chain verifies against the STORED digest.
    ADD COLUMN row_salt      BYTEA,
    -- H(version || salt || the row's committed fields)
    ADD COLUMN row_hash      BYTEA,
    -- The previous sealed row's chain_hash; NULL only for the first row ever sealed.
    ADD COLUMN prev_hash     BYTEA,
    -- H(prev_hash || seq || row_hash) — what the NEXT row commits to.
    ADD COLUMN chain_hash    BYTEA,
    -- Which digest encoding sealed this row, so the encoding can change without invalidating history.
    ADD COLUMN chain_version SMALLINT;

-- A sealed row has all of it or none of it: a half-sealed row would verify as tampered with.
ALTER TABLE audit_event
    ADD CONSTRAINT audit_event_seal_is_complete CHECK (
        (seq IS NULL AND row_salt IS NULL AND row_hash IS NULL AND chain_hash IS NULL AND chain_version IS NULL)
        OR (seq IS NOT NULL AND row_salt IS NOT NULL AND row_hash IS NOT NULL AND chain_hash IS NOT NULL
            AND chain_version IS NOT NULL));

-- Two rows at the same position would make "which one is the real chain" unanswerable, and the sealer's
-- single-writer lock is an application guarantee — this is the one that holds under a second process.
CREATE UNIQUE INDEX uq_audit_event_seq ON audit_event (seq) WHERE seq IS NOT NULL;

-- The sealer's own query: the next batch to seal, oldest first.
CREATE INDEX idx_audit_event_unsealed ON audit_event (id) WHERE seq IS NULL;

-- The chain's head, kept OUTSIDE audit_event on purpose.
--
-- Deleting the newest rows leaves no gap and no broken link, so a walk of audit_event alone cannot notice it —
-- and asking that same table for its own highest position is circular: the answer disappears along with the
-- rows. A separate row that says where the chain ended makes truncation a disagreement rather than silence.
-- It does not make truncation impossible (this table is writable too); it makes it a second deliberate edit,
-- and it is the value a later step publishes externally so that even the operator cannot quietly rewrite it.
CREATE TABLE audit_chain_head (
    id         SMALLINT PRIMARY KEY CHECK (id = 1),   -- exactly one head, enforced rather than assumed
    seq        BIGINT      NOT NULL,
    chain_hash BYTEA       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
