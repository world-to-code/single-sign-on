package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.authpolicy.policy.AuthPolicySpec;
import com.example.sso.authpolicy.policy.AuthPolicyUpdate;
import com.example.sso.shared.Page;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Presentation-facing adapter for the auth-policy admin API: delegates to {@link AuthPolicyAdminService}
 * and projects each result to {@link PolicyView}. Takes domain commands (the api request maps itself to
 * a command via {@code toSpec()}/{@code toUpdate()}), so the application layer never depends on the api.
 *
 * <p>Each projecting method (list/create/update) is {@code @Transactional} so the domain read/write AND
 * the {@link PolicyView} projection run in ONE session: {@code PolicyView.of} navigates the policy's LAZY
 * {@code steps}/factors and assignment sets, which would otherwise fail on the detached entity
 * ({@code LazyInitializationException}). {@code delete} does no projection, so it needs no transaction here.
 */
@Service
@RequiredArgsConstructor
public class PolicyAdminService {

    private final AuthPolicyAdminService service;

    @Transactional(readOnly = true)
    public Page<PolicyView> list(int page, int size) {
        return Page.of(service.listAll().stream().map(PolicyView::of).toList(), page, size);
    }

    @Transactional
    public PolicyView create(AuthPolicySpec spec) {
        return PolicyView.of(service.create(spec));
    }

    @Transactional
    public PolicyView update(UUID id, AuthPolicyUpdate update) {
        return PolicyView.of(service.update(id, update));
    }

    public void delete(UUID id) {
        service.delete(id);
    }
}
