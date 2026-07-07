package com.example.sso.admin.internal.organization.api;

import com.example.sso.admin.internal.organization.application.OrganizationAdminService;
import com.example.sso.admin.internal.shared.security.CanManageOrgMembers;
import com.example.sso.admin.internal.shared.security.CanViewOrg;
import com.example.sso.organization.OrganizationView;
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

/** Platform-admin API for the organization (tenant) registry and its membership. */
@RestController
@RequestMapping("/api/admin/organizations")
@RequiredArgsConstructor
public class AdminOrganizationController {

    private final OrganizationAdminService organizations;

    @GetMapping
    @RequirePermission(Permissions.ORG_READ)
    public Page<OrganizationView> organizations(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return organizations.list(page, size);
    }

    @GetMapping("/{id}")
    @CanViewOrg
    public OrganizationView organization(@PathVariable UUID id) {
        return organizations.get(id);
    }

    @PostMapping
    @RequirePermission(Permissions.ORG_CREATE)
    @RequireStepUp
    public ResponseEntity<OrganizationView> createOrganization(@Valid @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organizations.create(request.toCommand()));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.ORG_UPDATE)
    @RequireStepUp
    public OrganizationView updateOrganization(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateOrganizationRequest request) {
        return organizations.update(id, request.name(), request.status());
    }

    @PutMapping("/{id}/passwordless-login")
    @RequirePermission(Permissions.ORG_UPDATE)
    @RequireStepUp
    public OrganizationView updatePasswordlessLogin(@PathVariable UUID id,
                                                    @Valid @RequestBody PasswordlessLoginRequest request) {
        return organizations.updatePasswordlessLogin(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.ORG_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> deleteOrganization(@PathVariable UUID id) {
        organizations.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members")
    @CanManageOrgMembers
    @RequireStepUp
    public ResponseEntity<Void> addMember(@PathVariable UUID id, @Valid @RequestBody OrgMemberRequest request) {
        organizations.addMember(id, request.userId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{userId}")
    @CanManageOrgMembers
    @RequireStepUp
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        organizations.removeMember(id, userId);
        return ResponseEntity.noContent().build();
    }
}
