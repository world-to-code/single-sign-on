-- Time-bounded role assignments.
--
-- Every grant in this system was until-revoked: a role handed out for one afternoon's incident stayed until
-- somebody remembered it. That is the zero-trust tenet the codebase was furthest from ("privilege is
-- time-boxed"), and it is the cheapest one to close — session TERMINATION on access change already works, so
-- an expiring grant has somewhere to land.
--
-- NULL means permanent, which is what every existing row is. The column is deliberately not defaulted: a grant
-- that silently acquired an expiry would end access nobody asked to end.
ALTER TABLE app_user_role
    ADD COLUMN expires_at TIMESTAMPTZ;

-- The sweeper's query: which grants are now past their expiry. Partial, because permanent grants are the
-- overwhelming majority and have nothing to scan for.
CREATE INDEX idx_app_user_role_expires_at ON app_user_role (expires_at) WHERE expires_at IS NOT NULL;
