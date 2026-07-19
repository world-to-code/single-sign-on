package com.example.sso.federation.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A verified upstream identity bound to a local account: within one organization, the {@code issuer} that
 * minted it plus the {@code subject} it asserted resolve to exactly one {@code userId}. Recorded only after a
 * login has passed every authorization gate, so the row means "this upstream identity WAS that account", never
 * a standing grant — every later login re-checks membership, enabled and lockout state.
 *
 * <p>{@link #orgId} is never null: federated sign-in resolves its organization before it starts and is
 * org-strict, so there is no tier-less link to record.
 */
@Entity
@Table(name = "federated_identity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FederatedIdentityLink extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** The upstream that minted this identity — the identity key, because an alias can be repointed. */
    @Column(nullable = false, columnDefinition = "text")
    private String issuer;

    /** The id_token {@code sub}: opaque, upstream-defined, and the only identifier OIDC keeps stable. */
    @Column(nullable = false, length = 255)
    private String subject;

    /** The alias this identity last signed in through — display and diagnostics only, never part of the key. */
    @Column(name = "provider_alias", nullable = false, length = 64)
    private String providerAlias;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    public static FederatedIdentityLink create(UUID orgId, String issuer, String subject, String providerAlias,
            UUID userId) {
        FederatedIdentityLink link = new FederatedIdentityLink();
        link.orgId = orgId;
        link.issuer = issuer;
        link.subject = subject;
        link.providerAlias = providerAlias;
        link.userId = userId;
        return link;
    }
}
