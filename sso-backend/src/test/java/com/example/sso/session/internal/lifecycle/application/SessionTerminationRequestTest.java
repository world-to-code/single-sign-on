package com.example.sso.session.internal.lifecycle.application;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable unit's identity: {@code key()} coalesces repeated failures for the SAME target (so they don't pile
 * up) while separating a username target from a member-id target and one org from another; {@code auditPrincipal}
 * names the subject for an audit event — the username when known, else the id.
 */
class SessionTerminationRequestTest {

    @Test
    void theKeyIsStablePerTargetAndDistinguishesSubjectKindOrgAndGlobal() {
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Same username + org → same key (coalesces); different org or subject kind → different key.
        assertThat(SessionTerminationRequest.forUser("alice", org).key())
                .isEqualTo(SessionTerminationRequest.forUser("alice", org).key())
                .isNotEqualTo(SessionTerminationRequest.forUser("alice", otherOrg).key())
                .isNotEqualTo(SessionTerminationRequest.forMember(userId, org).key());
        // A global (null-org) target has a stable key too and does not collide with an org-scoped one.
        assertThat(new SessionTerminationRequest(null, "alice", null).key())
                .isNotEqualTo(SessionTerminationRequest.forUser("alice", org).key());
    }

    @Test
    void theAuditPrincipalIsTheUsernameOrTheIdWhenUnresolved() {
        UUID org = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        assertThat(SessionTerminationRequest.forUser("alice", org).auditPrincipal()).isEqualTo("alice");
        assertThat(SessionTerminationRequest.forMember(userId, org).auditPrincipal()).isEqualTo("user:" + userId);
    }
}
