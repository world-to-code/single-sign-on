-- Friendly display name for SAML relying parties, shown in the app lists (falls back to entityId when null).
ALTER TABLE saml_relying_party ADD COLUMN display_name varchar(256);
