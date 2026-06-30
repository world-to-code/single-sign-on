-- The per-policy cookie "Secure" toggle was never read: the session cookie's Secure attribute is
-- enforced by deployment config (server.servlet.session.cookie.secure) in production. Drop the dead
-- column to remove the misleading, no-op admin control.
ALTER TABLE session_policy DROP COLUMN cookie_secure;
