package com.example.sso.scim.internal.domain;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A bearer token authorizing SCIM provisioning calls. Only the token's SHA-256 hash is
 * persisted. Created via constructor; immutable after creation — no setters.
 * A token is either GLOBAL ({@code orgId} null — a platform token) or owned by one tenant; authenticating
 * with it binds that org so SCIM provisioning is confined to the tenant.
 */
@Entity
@Table(name = "scim_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ScimToken extends AuditedEntity implements OrgOwned {

    @Column(length = 200)
    private String description;

    // NULL = a GLOBAL/platform token; non-null = owned by that organization. Fixed at creation.
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * The administrator who issued this token, or null when nobody is on record (a seeder, or a token that
     * predates the column). A SCIM client writes attributes, so this is what makes the source it represents
     * accountable to the attribute-provenance guards — without it, nothing it fills can grant a role or select
     * a policy. Null is fail-closed, not permissive.
     */
    @Column(name = "issued_by")
    private UUID issuedBy;

    public ScimToken(String description, String tokenHash, Instant expiresAt, UUID orgId, UUID issuedBy) {
        this.description = description;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.orgId = orgId;
        this.issuedBy = issuedBy;
    }

    public boolean isActiveAt(Instant now) {
        return enabled && (expiresAt == null || now.isBefore(expiresAt));
    }
}
