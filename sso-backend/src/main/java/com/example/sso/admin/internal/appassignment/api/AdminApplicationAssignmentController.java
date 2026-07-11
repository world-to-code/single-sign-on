package com.example.sso.admin.internal.appassignment.api;

import com.example.sso.admin.internal.appassignment.application.AppAssignmentAdminService;
import com.example.sso.portal.access.AppAssignmentView;
import com.example.sso.portal.access.AppPolicyRequest;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.portal.access.AssignAppRequest;
import com.example.sso.shared.Page;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for assigning applications to users/groups and setting per-app sign-on policy. */
@RestController
@RequestMapping("/api/admin/applications")
@RequiredArgsConstructor
public class AdminApplicationAssignmentController {

    private final AppAssignmentAdminService applications;

    @GetMapping
    @RequirePermission(Permissions.APP_ASSIGNMENT_READ)
    public Page<ApplicationView> applications(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return applications.listApplications(page, size);
    }

    @GetMapping("/{type}/{id}/assignments")
    @RequirePermission(Permissions.APP_ASSIGNMENT_READ)
    public List<AppAssignmentView> appAssignments(@PathVariable AppType type, @PathVariable String id) {
        return applications.assignmentsForApp(type, id);
    }

    @PostMapping("/assignments")
    @RequirePermission(Permissions.APP_ASSIGNMENT_ASSIGN)
    @RequireStepUp
    public ResponseEntity<AppAssignmentView> assignApp(@Valid @RequestBody AssignAppRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(applications.assign(request));
    }

    @DeleteMapping("/assignments/{id}")
    @RequirePermission(Permissions.APP_ASSIGNMENT_UNASSIGN)
    @RequireStepUp
    public ResponseEntity<Void> unassignApp(@PathVariable UUID id) {
        applications.unassign(id);
        return ResponseEntity.noContent().build();
    }

    /** Sets (or clears, when requiredPolicyId is blank) the app-level sign-on policy for an application. */
    @PutMapping("/{type}/{id}/policy")
    @RequirePermission(Permissions.APP_ASSIGNMENT_ASSIGN)
    @RequireStepUp
    public ResponseEntity<Void> setAppPolicy(@PathVariable AppType type, @PathVariable String id,
                                             @RequestBody AppPolicyRequest request) {
        applications.setAppPolicy(type, id, request.requiredPolicyId());
        return ResponseEntity.noContent().build();
    }
}
