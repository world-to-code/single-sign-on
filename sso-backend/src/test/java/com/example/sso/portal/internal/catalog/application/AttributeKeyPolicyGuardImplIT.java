package com.example.sso.portal.internal.catalog.application;

import com.example.sso.metadata.AttributeKeyPolicyGuard;
import com.example.sso.metadata.AttributePredicate;
import com.example.sso.metadata.AttributePredicateGroup;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.SessionPolicySpec;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.rbac.Permissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard against a real database, because the decision it makes depends on what RLS SHOWS it.
 *
 * <p>{@code AttributeKeyPolicyGuardImplTest} pins the tier logic with mocked repositories, and cannot see the
 * fact that makes that logic necessary: the RLS policies on {@code policy_binding} and
 * {@code policy_binding_condition} deliberately admit {@code org_id IS NULL}, so a tenant's reverse lookup
 * returns the PLATFORM tier's bindings too. Feed the guard mocks and a tier check looks like belt-and-braces;
 * feed it the real policies and it is the only thing standing between a tenant admin and a key a platform
 * binding decides by — the policy permissions are tenant-grantable, so they hold the same permission NAME.
 *
 * <p>The other direction matters as much: attribute keys are tenant-CHOSEN names, so two tenants both using
 * "clearance" is the expected case, and one tenant's binding must not refuse the other's write.
 */
class AttributeKeyPolicyGuardImplIT extends AbstractIntegrationTest {

    /**
     * A fresh key per test. The platform-tier case writes a binding with {@code org_id IS NULL}, which no
     * organization delete can cascade away — it outlives the test and would then refuse every later one,
     * whichever tenant asked. Isolating by name is cheaper and more honest than deleting global rows.
     */
    private String key;

    @Autowired AttributeKeyPolicyGuard guard;
    @Autowired SessionPolicyService sessionPolicies;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void freshKey() {
        key = "clearance-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        drop(orgA);
        drop(orgB);
    }

    /** A tenant's own binding, and an actor who could set its policy: the write goes ahead. */
    @Test
    void anActorWhoCouldSetTheBindingsPolicyMayTakeTheKey() {
        orgA = org();
        bindOn(orgA, key);
        holding(Permissions.SESSION_POLICY_UPDATE, Permissions.POLICY_UPDATE);

        assertThat(orgContext.callInOrg(orgA, () -> guard.keysBeyondAuthority(Set.of(key)))).isEmpty();
    }

    /** Same tenant, same binding, an actor without the permission: refused. */
    @Test
    void anActorWithoutThePolicyPermissionMayNotTakeTheKey() {
        orgA = org();
        bindOn(orgA, key);
        holding(Permissions.ATTRIBUTE_DEFINITION_WRITE);

        assertThat(orgContext.callInOrg(orgA, () -> guard.keysBeyondAuthority(Set.of(key))))
                .containsExactly(key);
    }

    /**
     * The tier decision, and the reason this test exists. RLS shows tenant A the PLATFORM's binding, the policy
     * permissions are tenant-grantable so A's admin holds the same NAME, and OrgTierGuard would still refuse
     * them that global policy row. Without the tier check the guard vouches for a binding the actor could never
     * have set — which is the one thing it exists to prevent.
     */
    @Test
    void aTenantMayNotTakeAKeyAPlatformBindingDecidesBy() {
        orgA = org();
        bindOn(null, key);                       // platform tier: org_id IS NULL
        holding(Permissions.SESSION_POLICY_UPDATE, Permissions.POLICY_UPDATE);

        assertThat(orgContext.callInOrg(orgA, () -> guard.keysBeyondAuthority(Set.of(key))))
                .as("the global row IS visible through RLS — the refusal is the tier, not blindness")
                .containsExactly(key);
    }

    /**
     * And the mirror, which is what stops the guard becoming a cross-tenant denial of configuration: attribute
     * keys are tenant-chosen names. Tenant B naming a key "clearance" must not be refused because tenant A
     * happens to bind on the same string.
     */
    @Test
    void oneTenantsBindingDoesNotRefuseAnothersWrite() {
        orgA = org();
        orgB = org();
        bindOn(orgA, key);
        holding(Permissions.ATTRIBUTE_DEFINITION_WRITE);   // deliberately NO policy permission

        assertThat(orgContext.callInOrg(orgB, () -> guard.keysBeyondAuthority(Set.of(key))))
                .as("A's condition is invisible to B, so nothing governs B's key")
                .isEmpty();
    }

    /** A key nothing binds on is free, whoever asks. */
    @Test
    void anUngovernedKeyIsFreeToTake() {
        orgA = org();
        bindOn(orgA, key);
        holding(Permissions.ATTRIBUTE_DEFINITION_WRITE);

        assertThat(orgContext.callInOrg(orgA, () -> guard.keysBeyondAuthority(Set.of("department-" + key)))).isEmpty();
    }

    /** Creates a session policy whose scope IS an attribute predicate, in {@code tier} (null = platform). */
    private void bindOn(UUID tier, String key) {
        AttributePredicateGroup group = AttributePredicateGroup.of(AttributePredicate.equals(key, "high"));
        SessionPolicySpec spec = new SessionPolicySpec(
                "guard-" + UUID.randomUUID().toString().substring(0, 8), 10, true, 480, 30, 15, "TOTP", 2,
                "TOTP", false, 0, false, "Lax", Set.of(), Set.of(), List.of(), Set.of(group));
        orgContext.runInOrg(tier, () -> sessionPolicies.create(spec));
    }

    private void holding(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private UUID org() {
        String slug = "guard-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private void drop(UUID orgId) {
        if (orgId != null) {
            organizations.delete(orgId);
        }
    }
}
