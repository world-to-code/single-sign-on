package com.example.sso.admin.internal.sessionpolicy.api;

import com.example.sso.admin.internal.sessionpolicy.application.SessionPolicyAdminService;
import com.example.sso.session.SessionPolicyRequest;
import com.example.sso.session.SessionPolicyView;
import com.example.sso.shared.Page;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for session policies (timeouts, re-auth cadence, concurrency, client binding). */
@RestController
@RequestMapping("/api/admin/session-policies")
@RequiredArgsConstructor
public class AdminSessionPolicyController {

    private final SessionPolicyAdminService sessionPolicies;

    @GetMapping
    @RequirePermission(Permissions.SESSION_POLICY_READ)
    public Page<SessionPolicyView> sessionPolicies(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return sessionPolicies.list(page, size);
    }

    @PostMapping
    @RequirePermission(Permissions.SESSION_POLICY_CREATE)
    @RequireStepUp
    public ResponseEntity<SessionPolicyView> createSessionPolicy(@Valid @RequestBody SessionPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionPolicies.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SESSION_POLICY_UPDATE)
    @RequireStepUp
    public SessionPolicyView updateSessionPolicy(@PathVariable UUID id,
                                                 @Valid @RequestBody SessionPolicyRequest request) {
        return sessionPolicies.update(id, request);
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SESSION_POLICY_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> deleteSessionPolicy(@PathVariable UUID id) {
        sessionPolicies.delete(id);
        return ResponseEntity.noContent().build();
    }
}
