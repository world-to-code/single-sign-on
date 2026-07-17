package com.example.sso.admin.internal.shared.application;

import com.example.sso.admin.internal.audit.application.AuditAccessPolicy;
import com.example.sso.admin.internal.audit.application.AuditScope;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditService;
import com.example.sso.crypto.RsaKeyService;
import com.example.sso.saml.credential.SamlCredentialService;
import com.example.sso.scim.IssueScimTokenRequest;
import com.example.sso.scim.ScimTokenIssued;
import com.example.sso.scim.ScimTokenService;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ForbiddenException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Thin admin facade over cross-feature read/issue operations that don't warrant their own admin
 * service: audit log, SCIM token issuance, and signing-key rotation (OIDC + SAML). User,
 * OAuth2-client, and SAML relying-party administration live in their own services. Each collaborator
 * is another module's public API, so this facade never reaches into module internals.
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private final AuditService auditService;
    private final ScimTokenService scimTokenService;
    private final RsaKeyService rsaKeyService;
    private final SamlCredentialService samlCredentialService;
    private final AdminAccessPolicy accessPolicy;
    private final AuditAccessPolicy auditAccessPolicy;

    /**
     * Recent audit events, optionally filtered to a single category (null = all categories). Scoping is
     * three-tiered: (1) the reader's CATEGORY grants — a single category needs its {@code audit:read:<cat>}
     * perm (the controller already 403s an unpermitted one), and the ALL view returns only permitted categories;
     * (2) the acting tenant (a tenant admin, or a super-admin drilled into an org — an un-drilled super-admin
     * sees only global events); (3) a resource delegate is narrowed to entries whose subject is inside their
     * subtree (and their own actions). Finally the actor's PII is redacted unless the reader holds
     * {@code audit:read:pii}.
     */
    public Page<AuditEntry> recentAudit(AuditCategory category, int page, int size) {
        // Defense-in-depth: the controller's @auditAccessPolicy.canRead gate is the point of record, but re-assert
        // here so a specific category can never be read without its grant even if that annotation is bypassed.
        if (!auditAccessPolicy.canRead(category)) {
            throw new ForbiddenException("not permitted to read this audit category");
        }
        UUID actingOrg = accessPolicy.actingOrg();
        List<AuditEntry> events = category == null
                ? auditService.recentByCategories(actingOrg, auditAccessPolicy.permittedCategories())
                : auditService.recentByCategory(actingOrg, category);
        AuditScope scope = accessPolicy.currentAuditScope();
        List<AuditEntry> visible = scope.unscoped() ? events : events.stream().filter(scope::permits).toList();
        if (!auditAccessPolicy.canReadPii()) {
            visible = visible.stream().map(AuditEntry::withoutPii).toList();
        }
        return Page.of(visible, page, size);
    }

    public ScimTokenIssued issueScimToken(IssueScimTokenRequest request) {
        Duration ttl = request.ttlDays() == null ? null : Duration.ofDays(request.ttlDays());
        return new ScimTokenIssued(scimTokenService.issue(request.description(), ttl), request.description());
    }

    public String rotateSigningKey() {
        return rsaKeyService.rotate();
    }

    /** The acting tier's JWKS retention: how many rotated-away signing keys stay published. */
    public int signingKeyRetention() {
        return rsaKeyService.retainedInactiveKeys();
    }

    public int updateSigningKeyRetention(int retainedInactiveKeys) {
        return rsaKeyService.updateRetainedInactiveKeys(retainedInactiveKeys);
    }

    /** Rotates the SAML signing key; SPs must re-fetch IdP metadata for the new certificate. */
    public String rotateSamlSigningKey() {
        return samlCredentialService.rotate();
    }
}
