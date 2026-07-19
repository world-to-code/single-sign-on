-- "An account holds at most one identity per upstream" was enforced only in application code: read
-- existsByOrgIdAndIssuerAndUserId, then insert. Two callbacks asserting DIFFERENT subjects for the same
-- account race straight past it — both read false, and both inserts satisfy (org_id, issuer, subject), which
-- differs between them. The account ends up with two permanent upstream owners and the guard never fires
-- again. A check-then-act is not a decision under concurrency; the constraint is.
--
-- Replaces ix_federated_identity_user, which covered the same columns non-uniquely.
DROP INDEX ix_federated_identity_user;
CREATE UNIQUE INDEX uq_federated_identity_account ON federated_identity (org_id, issuer, user_id);
