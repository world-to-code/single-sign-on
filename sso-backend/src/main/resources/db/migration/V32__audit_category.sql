-- Coarse category for each audit event so the admin log can be viewed by category (authorization,
-- authentication, session, access, app-access, admin, user-action, system). Derived from the event
-- type at record time; existing rows default to SYSTEM.
ALTER TABLE audit_event ADD COLUMN category varchar(32) NOT NULL DEFAULT 'SYSTEM';
