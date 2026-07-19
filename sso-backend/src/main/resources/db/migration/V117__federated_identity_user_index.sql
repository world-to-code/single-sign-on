-- V116 indexed (org_id, issuer, user_id), which serves the "does this account already hold an identity at this
-- issuer?" guard but CANNOT serve a bare user_id predicate, because org_id leads it. Deleting an app_user
-- cascades into federated_identity on user_id, so every account deletion (and every org deletion, which
-- cascades through app_user) was left to sequential-scan this table. Index the FK itself.
CREATE INDEX ix_federated_identity_user_fk ON federated_identity (user_id);
