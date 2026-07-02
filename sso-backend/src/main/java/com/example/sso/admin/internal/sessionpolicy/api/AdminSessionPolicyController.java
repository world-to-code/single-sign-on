package com.example.sso.admin.internal.sessionpolicy.api;

import com.example.sso.admin.internal.shared.api.RequestIds;

import com.example.sso.session.SessionPolicyRequest;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.SessionPolicySpec;
import com.example.sso.session.SessionPolicyUpdate;
import com.example.sso.session.SessionPolicyView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for session policies (timeouts, re-auth cadence, concurrency, client binding). */
@RestController
@RequestMapping("/api/admin/session-policies")
@RequiredArgsConstructor
public class AdminSessionPolicyController {

    private final SessionPolicyService sessionPolicy;

    @GetMapping
    @RequirePermission(Permissions.SESSION_POLICY_READ)
    public List<SessionPolicyView> sessionPolicies() {
        return sessionPolicy.listAll().stream().map(SessionPolicyView::of).toList();
    }

    @PostMapping
    @RequirePermission(Permissions.SESSION_POLICY_CREATE)
    public ResponseEntity<SessionPolicyView> createSessionPolicy(@Valid @RequestBody SessionPolicyRequest request) {
        SessionPolicyView created = SessionPolicyView.of(sessionPolicy.create(new SessionPolicySpec(request.name(),
                request.priority(), request.enabled(), request.absoluteTimeoutMinutes(), request.idleTimeoutMinutes(),
                request.reauthIntervalMinutes(), request.reauthFactors(), request.bindClient(),
                request.maxConcurrentSessions(), request.rotateOnReauth(), request.cookieSameSite(),
                RequestIds.toUuidSet(request.assignedUserIds()), RequestIds.toUuidSet(request.assignedRoleIds()))));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SESSION_POLICY_UPDATE)
    public SessionPolicyView updateSessionPolicy(@PathVariable UUID id,
                                                 @Valid @RequestBody SessionPolicyRequest request) {
        return SessionPolicyView.of(sessionPolicy.update(id, new SessionPolicyUpdate(request.priority(),
                request.enabled(), request.absoluteTimeoutMinutes(), request.idleTimeoutMinutes(),
                request.reauthIntervalMinutes(), request.reauthFactors(), request.bindClient(),
                request.maxConcurrentSessions(), request.rotateOnReauth(), request.cookieSameSite(),
                RequestIds.toUuidSet(request.assignedUserIds()), RequestIds.toUuidSet(request.assignedRoleIds()))));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SESSION_POLICY_DELETE)
    public ResponseEntity<Void> deleteSessionPolicy(@PathVariable UUID id) {
        sessionPolicy.delete(id);
        return ResponseEntity.noContent().build();
    }
}
