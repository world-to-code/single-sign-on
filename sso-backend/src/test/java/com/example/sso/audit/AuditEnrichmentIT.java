package com.example.sso.audit;

import com.example.sso.audit.internal.domain.AuditEvent;
import com.example.sso.audit.internal.domain.AuditEventRepository;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end persistence of the enriched audit event against Testcontainers (so V108's columns and the
 * entity mapping are exercised for real). Recorded off any request thread, so the client context is
 * legitimately null — the point here is that the actor classification, severity, reason, and org all
 * persist and round-trip through the read projection without the enrichment ever breaking the write.
 */
class AuditEnrichmentIT extends AbstractIntegrationTest {

    @Autowired
    AuditService audit;
    @Autowired
    AuditEventRepository repository;
    @Autowired
    UserService userService;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        repository.deleteAll();
    }

    @Test
    void classifiesAServicePrincipalAndPersistsSeverityAndNullClientOffRequest() {
        audit.record(new AuditRecord(AuditType.SCIM_TOKEN_CHANGED, "scim-client", true, "issued", null, orgId));

        AuditEvent saved = only();
        assertThat(saved.getActorType()).isEqualTo(AuditActorType.SERVICE);
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getSeverity()).isEqualTo(AuditSeverity.INFO);
        assertThat(saved.getUserAgent()).isNull();   // no request bound off the recording thread
        assertThat(saved.getDevice()).isNull();
        assertThat(saved.getRequestId()).isNull();
        assertThat(saved.getOrgId()).isEqualTo(orgId);
    }

    @Test
    void anUnresolvableUsernameIsAnonymousAndAFailureIsAtLeastWarning() {
        audit.record(new AuditRecord(AuditType.AUTH_FAILURE, "ghost", false, "bad password", "198.51.100.7", orgId));

        AuditEvent saved = only();
        assertThat(saved.getActorType()).isEqualTo(AuditActorType.ANONYMOUS);
        assertThat(saved.getSeverity()).isEqualTo(AuditSeverity.WARNING);
        assertThat(saved.getRemoteIp()).isEqualTo("198.51.100.7"); // the caller's explicit IP is kept
    }

    @Test
    void aSecuritySignificantEventIsCriticalAndReasonRoundTripsThroughTheReadProjection() {
        audit.record(new AuditRecord(AuditType.SIGNING_KEY_ROTATED, "system:rotation", true, "rotate", null, orgId)
                .withReason("scheduled rotation"));

        AuditEvent saved = only();
        assertThat(saved.getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(saved.getSeverity()).isEqualTo(AuditSeverity.CRITICAL);
        assertThat(saved.getReason()).isEqualTo("scheduled rotation");

        AuditEntry entry = audit.recent(orgId).getFirst();
        assertThat(entry.severity()).isEqualTo(AuditSeverity.CRITICAL);
        assertThat(entry.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(entry.reason()).isEqualTo("scheduled rotation");
    }

    @Test
    void capturesTheClientContextFromABoundRequestAndFallsBackToItsIpThroughTheRealColumns() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.42");
        request.addHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120.0 Safari/537.36");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        // No explicit remoteIp on the record, so the captured client IP must fill the column.
        audit.record(new AuditRecord(AuditType.SCIM_TOKEN_CHANGED, "scim-client", true, "issued", null, orgId));

        AuditEvent saved = only();
        assertThat(saved.getUserAgent()).contains("Chrome/120.0");
        assertThat(saved.getDevice()).isEqualTo("Chrome on Windows");
        assertThat(saved.getRequestId()).isNotBlank();
        assertThat(saved.getRemoteIp()).isEqualTo("203.0.113.42"); // fell back to the captured client IP
    }

    @Test
    void anUnverifiedPrincipalIsNotEnrichedAndPersistsAsAnonymous() {
        // A failed/pre-auth login carries a caller-supplied username; even if it matched an account it must
        // persist name-only (no id/email), so a tenant can't harvest identities via failed logins.
        audit.record(new AuditRecord(AuditType.AUTH_FAILURE, "someone", false, "bad password", "10.0.0.9", orgId)
                .unverifiedActor());

        AuditEvent saved = only();
        assertThat(saved.getActorType()).isEqualTo(AuditActorType.ANONYMOUS);
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getActorEmail()).isNull();
        assertThat(saved.getSeverity()).isEqualTo(AuditSeverity.WARNING); // failure floor still applied
    }

    @Test
    void aVerifiedPrincipalEnrichesToTheAccountWhileTheSameNameUnverifiedStaysAnonymous() {
        UserAccount actor = userService.createUser(new NewUser(
                "audit-actor", "audit-actor@example.com", "Audit Actor", "S3cret!pw9", Set.of("ROLE_USER")));
        UUID actorOrg = actor.getOrgId();
        try {
            // Verified (authenticated) principal → resolved to the real account.
            audit.record(new AuditRecord(AuditType.AUTH_SUCCESS, "audit-actor", true, null, null, actorOrg));
            AuditEvent verified = only();
            assertThat(verified.getActorType()).isEqualTo(AuditActorType.USER);
            assertThat(verified.getActorId()).isEqualTo(actor.getId());
            assertThat(verified.getActorEmail()).isEqualTo("audit-actor@example.com");
            assertThat(verified.getActorDisplay()).isEqualTo("Audit Actor");

            // SAME username but UNVERIFIED (a failed/pre-auth login) → never resolved, name only.
            repository.deleteAll();
            audit.record(new AuditRecord(AuditType.AUTH_FAILURE, "audit-actor", false, null, null, actorOrg)
                    .unverifiedActor());
            AuditEvent unverified = only();
            assertThat(unverified.getActorType()).isEqualTo(AuditActorType.ANONYMOUS);
            assertThat(unverified.getActorId()).isNull();
            assertThat(unverified.getActorEmail()).isNull();
        } finally {
            userService.delete(actor.getId());
        }
    }

    private AuditEvent only() {
        return repository.findAll().getFirst();
    }
}
