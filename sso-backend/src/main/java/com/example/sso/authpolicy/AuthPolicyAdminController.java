package com.example.sso.authpolicy;

import com.example.sso.user.Permissions;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin CRUD for authentication policies (requires the {@code policy:manage} permission).
 */
@RestController
@RequestMapping("/api/admin/auth-policies")
@RequiredArgsConstructor
public class AuthPolicyAdminController {
    private final AuthPolicyAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.POLICY_MANAGE + "')")
    public List<PolicyView> list() {
        return service.listAll().stream().map(AuthPolicyAdminController::toView).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.POLICY_MANAGE + "')")
    public ResponseEntity<PolicyView> create(@Valid @RequestBody PolicyRequest request) {
        PolicyView created = toView(service.create(request.name(), request.priority(), request.enabled(),
                request.appliesToLogin() == null || request.appliesToLogin(),
                request.allowEnrollmentAtLogin() == null || request.allowEnrollmentAtLogin(),
                steps(request), ids(request.assignedUserIds()), ids(request.assignedRoleIds()),
                freshness(request)));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.POLICY_MANAGE + "')")
    public PolicyView update(@PathVariable UUID id, @Valid @RequestBody PolicyRequest request) {
        return toView(service.update(id, request.priority(), request.enabled(),
                request.appliesToLogin() == null || request.appliesToLogin(),
                request.allowEnrollmentAtLogin() == null || request.allowEnrollmentAtLogin(),
                steps(request), ids(request.assignedUserIds()), ids(request.assignedRoleIds()),
                freshness(request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.POLICY_MANAGE + "')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static List<Set<AuthFactor>> steps(PolicyRequest request) {
        return request.steps().stream()
                .map(step -> step.stream().map(AuthFactor::valueOf).collect(Collectors.toSet()))
                .toList();
    }

    private static Set<UUID> ids(List<String> values) {
        return values == null ? Set.of() : values.stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    /** Default the step-up freshness to 15 minutes when omitted. */
    private static int freshness(PolicyRequest request) {
        return request.stepUpFreshnessMinutes() == null ? 15 : request.stepUpFreshnessMinutes();
    }

    private static PolicyView toView(AuthPolicy policy) {
        List<List<String>> steps = policy.getSteps().stream()
                .map(step -> step.getAllowedFactors().stream().map(AuthFactor::name).sorted().toList())
                .toList();
        return new PolicyView(policy.getId().toString(), policy.getName(), policy.getPriority(), policy.isEnabled(),
                policy.isAppliesToLogin(), policy.isAllowEnrollmentAtLogin(), steps,
                policy.getAssignedUserIds().stream().map(UUID::toString).toList(),
                policy.getAssignedRoleIds().stream().map(UUID::toString).toList(),
                policy.getStepUpFreshnessMinutes());
    }
}
