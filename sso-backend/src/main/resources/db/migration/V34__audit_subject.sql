-- Structured subject on audit events, so the admin log can be scoped to a delegated admin's subtree.
-- Existing rows have no recorded subject; NONE means "no scopeable subject" (visible only to super admins).
ALTER TABLE audit_event ADD COLUMN subject_type varchar(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE audit_event ADD COLUMN subject_id varchar(255);
