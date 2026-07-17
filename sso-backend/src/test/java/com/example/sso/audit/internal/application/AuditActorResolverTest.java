package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditActorType;
import com.example.sso.audit.internal.domain.AuditActorInfo;
import com.example.sso.user.account.UserActorView;
import com.example.sso.user.account.UserService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resolving a bare principal into a structured actor: reserved machine/system names are classified by
 * name (no lookup), a resolvable username becomes a USER carrying id/email/display, a miss (unknown
 * username, IP-only caller, or an ambiguous global/tenant collision the service declines to guess) is
 * ANONYMOUS, and a lookup that throws degrades gracefully to name-only so the audit write is never broken
 * by enrichment.
 */
class AuditActorResolverTest {

    private final UserService users = mock(UserService.class);
    private final AuditActorResolver resolver = new AuditActorResolver(users);

    private final UUID orgId = UUID.randomUUID();

    @Test
    void aResolvableUsernameBecomesAUserWithIdEmailAndDisplay() {
        UUID userId = UUID.randomUUID();
        when(users.findActor("alice", orgId))
                .thenReturn(Optional.of(new UserActorView(userId, "alice@acme.test", "Alice A")));

        AuditActorInfo actor = resolver.resolve("alice", orgId);

        assertThat(actor.type()).isEqualTo(AuditActorType.USER);
        assertThat(actor.id()).isEqualTo(userId);
        assertThat(actor.email()).isEqualTo("alice@acme.test");
        assertThat(actor.displayName()).isEqualTo("Alice A");
        assertThat(actor.name()).isEqualTo("alice");
    }

    @Test
    void aNullOrgLookupStillResolvesAUser() {
        when(users.findActor(eq("carol"), isNull()))
                .thenReturn(Optional.of(new UserActorView(UUID.randomUUID(), "carol@acme.test", "Carol C")));

        assertThat(resolver.resolve("carol", null).type()).isEqualTo(AuditActorType.USER);
    }

    @Test
    void theScimClientIsAServiceActorWithNoLookup() {
        assertThat(resolver.resolve("scim-client", orgId).type()).isEqualTo(AuditActorType.SERVICE);
    }

    @Test
    void aSystemPrefixedPrincipalIsASystemActor() {
        AuditActorInfo actor = resolver.resolve("system:mapping-rule", orgId);
        assertThat(actor.type()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(actor.name()).isEqualTo("system:mapping-rule");
    }

    @Test
    void unknownAnonymousAndBlankPrincipalsAreAnonymous() {
        assertThat(resolver.resolve("unknown", orgId).type()).isEqualTo(AuditActorType.ANONYMOUS);
        assertThat(resolver.resolve("anonymous", orgId).type()).isEqualTo(AuditActorType.ANONYMOUS);
        assertThat(resolver.resolve("  ", orgId).type()).isEqualTo(AuditActorType.ANONYMOUS);
        assertThat(resolver.resolve(null, orgId).type()).isEqualTo(AuditActorType.ANONYMOUS);
    }

    @Test
    void anUnresolvableOrAmbiguousUsernameIsAnonymousButKeepsTheName() {
        when(users.findActor(eq("ghost"), any())).thenReturn(Optional.empty());

        AuditActorInfo actor = resolver.resolve("ghost", orgId);

        assertThat(actor.type()).isEqualTo(AuditActorType.ANONYMOUS);
        assertThat(actor.name()).isEqualTo("ghost");
        assertThat(actor.id()).isNull();
    }

    @Test
    void aLookupThatThrowsDegradesToNameOnlyNeverBreakingTheWrite() {
        when(users.findActor(any(), any())).thenThrow(new RuntimeException("db down"));

        AuditActorInfo actor = resolver.resolve("dave", orgId);

        assertThat(actor.type()).isEqualTo(AuditActorType.ANONYMOUS);
        assertThat(actor.name()).isEqualTo("dave");
    }
}
