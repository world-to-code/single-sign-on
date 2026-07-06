-- One-time set-password invitation for tenant onboarding: a newly provisioned org admin redeems it to set
-- their password and activate the account. GLOBAL (not org-scoped) — resolved by token hash on an
-- unauthenticated request; the org linkage is the user's membership, not this row. Only the SHA-256 hash of
-- the token is stored; the raw token lives only in the emailed link. Single-use (used_at) and time-boxed.
CREATE TABLE onboarding_invitation (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    user_id     uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash  varchar(128) NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz
);

CREATE INDEX idx_onboarding_invitation_user ON onboarding_invitation (user_id);
