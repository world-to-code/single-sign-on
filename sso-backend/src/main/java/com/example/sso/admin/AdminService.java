package com.example.sso.admin;

import com.example.sso.audit.AuditEventRepository;
import com.example.sso.crypto.RsaKeyService;
import com.example.sso.saml.SamlCredentialService;
import com.example.sso.scim.ScimTokenService;
import com.example.sso.scim.IssueScimTokenRequest;
import com.example.sso.scim.ScimTokenIssued;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Thin admin facade over cross-feature read/issue operations that don't warrant their own admin
 * service: audit log, SCIM token issuance, and signing-key rotation (OIDC + SAML). User,
 * OAuth2-client, and SAML relying-party administration live in their own services.
 */
@Service
public class AdminService {

    private final AuditEventRepository auditEvents;
    private final ScimTokenService scimTokenService;
    private final RsaKeyService rsaKeyService;
    private final SamlCredentialService samlCredentialService;

    public AdminService(AuditEventRepository auditEvents, ScimTokenService scimTokenService,
                        RsaKeyService rsaKeyService, SamlCredentialService samlCredentialService) {
        this.auditEvents = auditEvents;
        this.scimTokenService = scimTokenService;
        this.rsaKeyService = rsaKeyService;
        this.samlCredentialService = samlCredentialService;
    }

    @Transactional(readOnly = true)
    public List<AuditView> recentAudit() {
        return auditEvents.findTop100ByOrderByOccurredAtDesc().stream()
                .map(event -> new AuditView(event.getId(), event.getOccurredAt(), event.getPrincipal(),
                        event.getType(), event.isSuccess(), event.getDetail()))
                .toList();
    }

    public ScimTokenIssued issueScimToken(IssueScimTokenRequest request) {
        Duration ttl = request.ttlDays() == null ? null : Duration.ofDays(request.ttlDays());
        return new ScimTokenIssued(scimTokenService.issue(request.description(), ttl), request.description());
    }

    public String rotateSigningKey() {
        return rsaKeyService.rotate().getKid();
    }

    /** Rotates the SAML signing key; SPs must re-fetch IdP metadata for the new certificate. */
    public String rotateSamlSigningKey() {
        return samlCredentialService.rotate();
    }
}
