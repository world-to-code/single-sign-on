package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.authpolicy.policy.AuthPolicySpec;
import com.example.sso.authpolicy.policy.AuthPolicyUpdate;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.authpolicy.policy.LoginAssignment;
import com.example.sso.authpolicy.policy.LoginAuthBindings;
import com.example.sso.shared.Page;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Presentation-facing adapter for the auth-policy admin API: delegates to {@link AuthPolicyAdminService}
 * and projects each result to {@link PolicyView}, joining in each policy's login scope from the
 * {@code policy_binding} matrix ({@link LoginAuthBindings#describe}). Takes domain commands (the api request
 * maps itself to a command via {@code toSpec()}/{@code toUpdate()}), so the application layer never depends
 * on the api.
 *
 * <p>Each projecting method (list/create/update) is {@code @Transactional} so the domain read/write, the
 * login-scope read, AND the {@link PolicyView} projection run in ONE session: {@code PolicyView.of} navigates
 * the policy's LAZY {@code steps}/factors (which would otherwise fail on the detached entity,
 * {@code LazyInitializationException}), and the just-written bindings are read back in the same tenant scope.
 * {@code delete} does no projection, so it needs no transaction here.
 */
@Service
@RequiredArgsConstructor
public class PolicyAdminService {

    private final AuthPolicyAdminService service;
    private final LoginAuthBindings loginBindings;

    @Transactional(readOnly = true)
    public Page<PolicyView> list(int page, int size) {
        List<AuthPolicyView> policies = service.listAll();
        Map<UUID, LoginAssignment> login = loginBindings.describe(policies.stream().map(AuthPolicyView::getId).toList());
        return Page.of(policies.stream().map(policy -> PolicyView.of(policy, login.get(policy.getId()))).toList(),
                page, size);
    }

    @Transactional
    public PolicyView create(AuthPolicySpec spec) {
        return withLoginScope(service.create(spec));
    }

    @Transactional
    public PolicyView update(UUID id, AuthPolicyUpdate update) {
        return withLoginScope(service.update(id, update));
    }

    public void delete(UUID id) {
        service.delete(id);
    }

    private PolicyView withLoginScope(AuthPolicyView policy) {
        LoginAssignment login = loginBindings.describe(List.of(policy.getId())).get(policy.getId());
        return PolicyView.of(policy, login);
    }
}
