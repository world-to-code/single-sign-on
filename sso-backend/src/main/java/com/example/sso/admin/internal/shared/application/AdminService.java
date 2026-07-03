package com.example.sso.admin.internal.shared.application;

import com.example.sso.admin.internal.audit.application.AuditScope;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditService;
import com.example.sso.crypto.RsaKeyService;
import com.example.sso.saml.SamlCredentialService;
import com.example.sso.scim.IssueScimTokenRequest;
import com.example.sso.scim.ScimTokenIssued;
import com.example.sso.scim.ScimTokenService;
import com.example.sso.shared.Page;
import java.time.Duration;
import java.util.List;
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

    /**
     * Recent audit events, optionally filtered to a single category (null = all categories) and always
     * scoped to the acting admin: a super admin sees everything; a delegate sees only entries whose
     * subject is inside their subtree (and their own actions).
     */
    public Page<AuditEntry> recentAudit(AuditCategory category, int page, int size) {
        List<AuditEntry> events = category == null ? auditService.recent() : auditService.recentByCategory(category);
        AuditScope scope = accessPolicy.currentAuditScope();
        List<AuditEntry> visible = scope.unscoped() ? events : events.stream().filter(scope::permits).toList();
        return Page.of(visible, page, size);
    }

    public ScimTokenIssued issueScimToken(IssueScimTokenRequest request) {
        Duration ttl = request.ttlDays() == null ? null : Duration.ofDays(request.ttlDays());
        return new ScimTokenIssued(scimTokenService.issue(request.description(), ttl), request.description());
    }

    public String rotateSigningKey() {
        return rsaKeyService.rotate();
    }

    /** Rotates the SAML signing key; SPs must re-fetch IdP metadata for the new certificate. */
    public String rotateSamlSigningKey() {
        return samlCredentialService.rotate();
    }
}
