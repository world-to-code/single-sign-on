-- Per-app step-up re-authentication window. When a policy is attached to an application, the user
-- must complete a DELIBERATE step-up for that app; it stays valid for this many minutes, after which
-- app entry challenges again. (Login alone never satisfies it — that is why an attached policy now
-- actually prompts instead of passing through on already-held factors.)
ALTER TABLE auth_policy ADD COLUMN step_up_freshness_minutes INT NOT NULL DEFAULT 15;
