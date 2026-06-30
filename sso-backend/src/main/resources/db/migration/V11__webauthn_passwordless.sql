-- Spring Security WebAuthn credential store for PRIMARY passwordless passkey login
-- (JdbcUserCredentialRepository / JdbcPublicKeyCredentialUserEntityRepository).
-- Schema mirrors spring-security-web's user-entities-schema.sql + user-credentials-schema-postgres.sql.
CREATE TABLE user_entities (
    id           varchar(1000) NOT NULL,
    name         varchar(100)  NOT NULL,
    display_name varchar(200),
    PRIMARY KEY (id)
);

CREATE TABLE user_credentials (
    credential_id                varchar(1000) NOT NULL,
    user_entity_user_id          varchar(1000) NOT NULL,
    public_key                   bytea         NOT NULL,
    signature_count              bigint,
    uv_initialized               boolean,
    backup_eligible              boolean       NOT NULL,
    authenticator_transports     varchar(1000),
    public_key_credential_type   varchar(100),
    backup_state                 boolean       NOT NULL,
    attestation_object           bytea,
    attestation_client_data_json bytea,
    created                      timestamp,
    last_used                    timestamp,
    label                        varchar(1000) NOT NULL,
    PRIMARY KEY (credential_id)
);
