package com.example.sso.admin.internal.sessionpolicy.application;

import com.example.sso.session.SessionPolicyRequest;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.SessionPolicyView;
import com.example.sso.shared.Page;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Presentation-facing adapter for the session-policy admin API: delegates to {@link SessionPolicyService}
 * (the request maps itself to the domain command) and projects results to {@link SessionPolicyView}.
 *
 * <p>Each mapping method is {@code @Transactional} so the domain read/write AND the {@link SessionPolicyView}
 * projection run in ONE session: {@code SessionPolicyView.of} navigates the policy's LAZY assignment sets,
 * which would otherwise fail on the detached entity ({@code LazyInitializationException}).
 */
@Service
@RequiredArgsConstructor
public class SessionPolicyAdminService {

    private final SessionPolicyService sessionPolicy;

    @Transactional(readOnly = true)
    public Page<SessionPolicyView> list(int page, int size) {
        return Page.of(sessionPolicy.listAll().stream().map(SessionPolicyView::of).toList(), page, size);
    }

    @Transactional
    public SessionPolicyView create(SessionPolicyRequest request) {
        return SessionPolicyView.of(sessionPolicy.create(request.toSpec()));
    }

    @Transactional
    public SessionPolicyView update(UUID id, SessionPolicyRequest request) {
        return SessionPolicyView.of(sessionPolicy.update(id, request.toUpdate()));
    }

    public void delete(UUID id) {
        sessionPolicy.delete(id);
    }
}
