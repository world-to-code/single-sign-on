-- Separate base login (sign-on) policies from app-only policies (used solely via app_assignment
-- .required_policy_id for per-app step-up). Only login policies participate in login resolution;
-- an unassigned login policy applies globally. Existing rows default to login policies.
ALTER TABLE auth_policy ADD COLUMN applies_to_login boolean NOT NULL DEFAULT TRUE;
