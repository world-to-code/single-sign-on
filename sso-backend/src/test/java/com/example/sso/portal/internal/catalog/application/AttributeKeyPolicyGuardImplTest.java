package com.example.sso.portal.internal.catalog.application;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingCondition;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingConditionRepository;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.portal.application.AppType;
import com.example.sso.user.rbac.Permissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admission-time question: may this administrator take control of an attribute key that a policy binding
 * uses to decide someone's authentication or session policy?
 *
 * <p>Every case here asserts the RESTRICTIVE direction as well as the permissive one. The version of this
 * guard that shipped and was reverted was mutation-checked only in the permissive direction, which is exactly
 * why nobody noticed it had become a way to suppress a binding org-wide.
 */
@ExtendWith(MockitoExtension.class)
class AttributeKeyPolicyGuardImplTest {

    private static final String KEY = "clearance";
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID BINDING = UUID.randomUUID();
    private static final UUID AUTH_POLICY = UUID.randomUUID();
    private static final UUID SESSION_POLICY = UUID.randomUUID();

    @Mock private PolicyBindingConditionRepository conditions;
    @Mock private PolicyBindingRepository bindings;
    @Mock private OrgContext orgContext;
    @InjectMocks private AttributeKeyPolicyGuardImpl guard;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void actingIn(UUID org) {
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.ofNullable(org));
    }

    private void actorHolds(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private void bindingOn(String key, UUID authPolicyId, UUID sessionPolicyId) {
        bindingOn(key, authPolicyId, sessionPolicyId, ORG);
    }

    private void bindingOn(String key, UUID authPolicyId, UUID sessionPolicyId, UUID bindingOrg) {
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(conditions.findByAttrKeyIn(any())).thenReturn(List.of(condition(key)));
        lenient().when(bindings.findAllById(Set.of(BINDING)))
                .thenReturn(List.of(binding(authPolicyId, sessionPolicyId, bindingOrg)));
    }

    private PolicyBindingCondition condition(String key) {
        return condition(key, BINDING);
    }

    private PolicyBindingCondition condition(String key, UUID bindingId) {
        return PolicyBindingCondition.of(bindingId,
                new AttributePredicate(key, AttributeOperator.EQUALS, "high", List.of()), ORG);
    }

    private PolicyBinding binding(UUID authPolicyId, UUID sessionPolicyId) {
        return binding(authPolicyId, sessionPolicyId, ORG);
    }

    private PolicyBinding binding(UUID authPolicyId, UUID sessionPolicyId, UUID bindingOrg) {
        PolicyBinding binding = PolicyBinding.forAttributeGroup(AppType.PORTAL, PortalApps.USER, bindingOrg);
        ReflectionTestUtils.setField(binding, "id", BINDING);
        binding.assignAuthPolicy(authPolicyId);
        binding.assignSessionPolicy(sessionPolicyId);
        return binding;
    }

    @Test
    void aKeyNoBindingTestsIsFreeToControl() {
        when(conditions.findByAttrKeyIn(Set.of(KEY))).thenReturn(List.of());

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).isEmpty();
        verify(bindings, never()).findAllById(any());
    }

    @Test
    void holdingTheAuthPolicyPermissionVouchesForAnAuthOnlyBinding() {
        bindingOn(KEY, AUTH_POLICY, null);
        actorHolds(Permissions.POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).isEmpty();
    }

    /** The escalation this exists to stop: aiming a directory at a key is not authority over policy. */
    @Test
    void connectorManagementAloneCannotTakeAGovernedKey() {
        bindingOn(KEY, AUTH_POLICY, null);
        actorHolds(Permissions.DIRECTORY_CONNECTOR_WRITE, Permissions.ATTRIBUTE_DEFINITION_WRITE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).containsExactly(KEY);
    }

    /**
     * A binding carrying both policies is two grants. Holding one is not a reason to skip the other's check —
     * the row is co-located, so a session policy riding along on an auth binding still has to be vouched for.
     */
    @Test
    void aBindingSettingBothPoliciesNeedsBothPermissions() {
        bindingOn(KEY, AUTH_POLICY, SESSION_POLICY);
        actorHolds(Permissions.POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).containsExactly(KEY);
    }

    @Test
    void aBindingSettingBothPoliciesPassesWithBothPermissions() {
        bindingOn(KEY, AUTH_POLICY, SESSION_POLICY);
        actorHolds(Permissions.POLICY_UPDATE, Permissions.SESSION_POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).isEmpty();
    }

    @Test
    void aSessionOnlyBindingIsNotVouchedForByTheAuthPolicyPermission() {
        bindingOn(KEY, null, SESSION_POLICY);
        actorHolds(Permissions.POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).containsExactly(KEY);
    }

    /** Fails closed: a condition naming a binding this reader cannot see is not read as "no binding". */
    @Test
    void aConditionWhoseBindingIsUnreadableIsRefused() {
        actingIn(ORG);
        lenient().when(conditions.findByAttrKeyIn(any())).thenReturn(List.of(condition(KEY)));
        when(bindings.findAllById(Set.of(BINDING))).thenReturn(List.of());
        actorHolds(Permissions.POLICY_UPDATE, Permissions.SESSION_POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).containsExactly(KEY);
    }

    @Test
    void anUnauthenticatedCallerVouchesForNothing() {
        bindingOn(KEY, AUTH_POLICY, null);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).containsExactly(KEY);
    }

    /**
     * Only the governed keys come back. A caller asking about several keys must not be refused wholesale —
     * the message names the key, and refusing an unrelated one would be a false accusation.
     */
    @Test
    void onlyTheGovernedKeyIsReported() {
        actingIn(ORG);
        when(conditions.findByAttrKeyIn(Set.of(KEY, "department"))).thenReturn(List.of(condition(KEY)));
        when(bindings.findAllById(Set.of(BINDING))).thenReturn(List.of(binding(AUTH_POLICY, null)));
        actorHolds(Permissions.DIRECTORY_CONNECTOR_WRITE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY, "department"))).containsExactly(KEY);
    }

    @Test
    void anEmptyRequestAsksTheDatabaseNothing() {
        assertThat(guard.keysBeyondAuthority(Set.of())).isEmpty();
        assertThat(guard.keysBeyondAuthority(null)).isEmpty();

        verify(conditions, never()).findByAttrKeyIn(any());
    }

    // --- tier awareness ----------------------------------------------------------------------------------

    /**
     * The policy permissions are TENANT-GRANTABLE, so an org admin holds the same permission NAME the platform
     * tier does. RLS still shows them the GLOBAL bindings, and a global binding really does govern their users
     * — but OrgTierGuard would refuse them that row. Holding the name is not authority over another tier.
     */
    @Test
    void aTenantAdminCannotVouchForAGlobalBindingEvenHoldingBothPermissions() {
        bindingOn(KEY, AUTH_POLICY, SESSION_POLICY, null);
        actingIn(ORG);
        actorHolds(Permissions.POLICY_UPDATE, Permissions.SESSION_POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).containsExactly(KEY);
    }

    @Test
    void aPlatformActorVouchesForAGlobalBinding() {
        bindingOn(KEY, AUTH_POLICY, null, null);
        actingIn(null);
        actorHolds(Permissions.POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).isEmpty();
    }

    /** And the mirror: another tenant's binding is no more vouchable than the platform's. */
    @Test
    void aPlatformActorCannotVouchForATenantsBinding() {
        bindingOn(KEY, AUTH_POLICY, null, ORG);
        actingIn(null);
        actorHolds(Permissions.POLICY_UPDATE, Permissions.SESSION_POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).containsExactly(KEY);
    }

    /**
     * The mirror of the auth-only case, and it is load-bearing: without it, demanding auth-policy authority
     * from a binding that sets NO auth policy survives as a mutant, and a tenant admin holding only the
     * session permission would be refused a key whose binding never involved an auth policy.
     */
    @Test
    void holdingTheSessionPolicyPermissionVouchesForASessionOnlyBinding() {
        bindingOn(KEY, null, SESSION_POLICY);
        actorHolds(Permissions.SESSION_POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).isEmpty();
    }

    @Test
    void aBindingSettingBothIsNotVouchedForByTheSessionPermissionAlone() {
        bindingOn(KEY, AUTH_POLICY, SESSION_POLICY);
        actorHolds(Permissions.SESSION_POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).containsExactly(KEY);
    }

    /**
     * One key can be tested by several bindings. Authority over one is not authority over the others — taking
     * the key steers every binding that reads it, so the quantifier is ALL, not ANY.
     */
    @Test
    void twoBindingsOnOneKeyNeedAuthorityOverBoth() {
        UUID otherBinding = UUID.randomUUID();
        PolicyBinding vouched = binding(AUTH_POLICY, null);
        PolicyBinding notVouched = PolicyBinding.forAttributeGroup(AppType.PORTAL, PortalApps.USER, ORG);
        ReflectionTestUtils.setField(notVouched, "id", otherBinding);
        notVouched.assignSessionPolicy(SESSION_POLICY);

        actingIn(ORG);
        // The vouched-for one LAST on purpose: a guard that let a later "may set" clear an earlier refusal
        // would still pass with the refusal last, so the order that can expose it is the one to assert.
        when(conditions.findByAttrKeyIn(Set.of(KEY)))
                .thenReturn(List.of(condition(KEY, otherBinding), condition(KEY)));
        when(bindings.findAllById(Set.of(otherBinding, BINDING))).thenReturn(List.of(notVouched, vouched));
        actorHolds(Permissions.POLICY_UPDATE);

        assertThat(guard.keysBeyondAuthority(Set.of(KEY))).containsExactly(KEY);
    }
}
