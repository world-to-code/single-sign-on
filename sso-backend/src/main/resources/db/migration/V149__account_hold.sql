-- A reversible hold on an account — the middle state between "nothing" and "disabled".
--
-- Everything this system could do to a suspicious account was binary: leave it alone, or disable it. A
-- detection system acts on SUSPICION, so the only two options it had were to do nothing or to take somebody's
-- account away on a probability. A hold is the third: end the sessions that exist, make the next sign-in prove
-- a second factor, and stop by itself.
--
-- expires_at is NOT NULL on purpose. A hold without one is a disable wearing a different name, and the whole
-- reason a machine may place this without a human in the loop is that it cannot outlast its own suspicion.
-- The upper bound on the duration is enforced in code (sso.account-hold.max-duration), where it can be a
-- tunable rather than a number frozen into the schema.
--
-- A lifted or expired hold is DELETED rather than flagged, for the reason the lapsed role grant is: the row
-- IS the hold, so keeping a dead one makes every future query responsible for remembering to exclude it. The
-- history lives in the audit trail, which is hash-chained (V147) and is where an investigator looks anyway.

CREATE TABLE account_hold (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The HELD USER's tenant, not the caller's: a hold placed by a platform operator on a tenant's user is
    -- the tenant's to see and to lift. NULL = a global/platform account.
    org_id         uuid REFERENCES organization (id) ON DELETE CASCADE,
    user_id        uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    reason         text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 200),
    -- Exactly one of these identifies who asked. An administrator is a person; a response system is a
    -- correlation id pointing back into the detection that raised it, and inventing a user for it would
    -- attribute a machine's decision to somebody who did not make it.
    placed_by      uuid REFERENCES app_user (id) ON DELETE SET NULL,
    correlation_id text                 CHECK (correlation_id IS NULL OR length(correlation_id) <= 128),
    placed_at      timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- One hold per account. Re-placing REPLACES it, so extending a hold is the same call that placed it and a
-- second detection cannot quietly stack a longer one behind the first.
CREATE UNIQUE INDEX uq_account_hold_user ON account_hold (user_id);

-- The sweeper's query, and the FK's index for the org cascade.
CREATE INDEX idx_account_hold_expires_at ON account_hold (expires_at);
CREATE INDEX idx_account_hold_org_id ON account_hold (org_id);

-- Defence in depth for anything that later queries this table in a tenant context. It is NOT what confines
-- the enforcement read: that one looks a hold up BY USER ID as an authoritative, RLS-bypassing lookup
-- (the posture UserService.orgIdOf already takes), because a security control that an unbound context
-- cannot see is a control that fails OPEN.
ALTER TABLE account_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_hold FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON account_hold
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id IS NULL
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
        OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = '')
    );
