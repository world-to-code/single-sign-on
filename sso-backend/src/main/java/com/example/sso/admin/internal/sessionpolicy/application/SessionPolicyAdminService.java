package com.example.sso.admin.internal.sessionpolicy.application;

import com.example.sso.session.policy.SessionAssignment;
import com.example.sso.session.policy.SessionBindings;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyRequest;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.SessionPolicyView;
import com.example.sso.shared.Page;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Presentation-facing adapter for the session-policy admin API: delegates to {@link SessionPolicyService}
 * (the request maps itself to the domain command) and projects results to {@link SessionPolicyView}, joining
 * in each policy's assignment scope from the {@code policy_binding} matrix ({@link SessionBindings#describe}).
 *
 * <p>Each mapping method is {@code @Transactional} so the domain read/write, the assignment read, AND the
 * {@link SessionPolicyView} projection run in ONE session: {@code SessionPolicyView.of} navigates the policy's
 * LAZY IP rules and the just-written bindings are read back in the same tenant scope.
 */
@Service
@RequiredArgsConstructor
public class SessionPolicyAdminService {

    private final SessionPolicyService sessionPolicy;
    private final SessionBindings sessionBindings;

    @Transactional(readOnly = true)
    public Page<SessionPolicyView> list(int page, int size) {
        List<SessionPolicyDetails> policies = sessionPolicy.listAll();
        Map<UUID, SessionAssignment> scope =
                sessionBindings.describe(policies.stream().map(SessionPolicyDetails::getId).toList());
        return Page.of(policies.stream().map(p -> SessionPolicyView.of(p, scope.get(p.getId()))).toList(), page, size);
    }

    @Transactional
    public SessionPolicyView create(SessionPolicyRequest request) {
        return withScope(sessionPolicy.create(request.toSpec()));
    }

    @Transactional
    public SessionPolicyView update(UUID id, SessionPolicyRequest request) {
        return withScope(sessionPolicy.update(id, request.toUpdate()));
    }

    public void delete(UUID id) {
        sessionPolicy.delete(id);
    }

    private SessionPolicyView withScope(SessionPolicyDetails policy) {
        SessionAssignment assignment = sessionBindings.describe(List.of(policy.getId())).get(policy.getId());
        return SessionPolicyView.of(policy, assignment);
    }
}
