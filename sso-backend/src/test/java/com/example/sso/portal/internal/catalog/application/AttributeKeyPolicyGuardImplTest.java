package com.example.sso.portal.internal.catalog.application;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingCondition;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingConditionRepository;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.portal.application.AppType;
import com.example.sso.user.rbac.Permissions;
import java.util.List;
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
    @InjectMocks private AttributeKeyPolicyGuardImpl guard;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void actorHolds(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private void bindingOn(String key, UUID authPolicyId, UUID sessionPolicyId) {
        lenient().when(conditions.findByAttrKeyIn(any())).thenReturn(List.of(condition(key)));
        lenient().when(bindings.findAllById(Set.of(BINDING)))
                .thenReturn(List.of(binding(authPolicyId, sessionPolicyId)));
    }

    private PolicyBindingCondition condition(String key) {
        return PolicyBindingCondition.of(BINDING,
                new AttributePredicate(key, AttributeOperator.EQUALS, "high", List.of()), ORG);
    }

    private PolicyBinding binding(UUID authPolicyId, UUID sessionPolicyId) {
        PolicyBinding binding = PolicyBinding.forAttributeGroup(AppType.PORTAL, PortalApps.USER, ORG);
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
}
