package com.example.sso.resource.internal.api;

import com.example.sso.resource.internal.catalog.application.ResourceAdminService;
import com.example.sso.resource.internal.catalog.application.ResourceDetailView;
import com.example.sso.resource.internal.catalog.application.ResourceTypeView;
import com.example.sso.resource.internal.catalog.application.ResourceView;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin CRUD for the resource DAG (types, nodes, edges, members, delegation grants). Edge/member/
 * grant mutations change WHO IS IN SCOPE under subtree enforcement, so they are step-up gated like
 * the other privilege-escalating admin actions; plain create/rename are not.
 */
@RestController
@RequestMapping("/api/admin/resources")
@RequiredArgsConstructor
public class ResourceAdminController {

    private final ResourceAdminService service;

    // --- Types ---

    @GetMapping("/types")
    @RequirePermission(Permissions.RESOURCE_READ)
    public List<ResourceTypeView> listTypes() {
        return service.listTypes();
    }

    @PostMapping("/types")
    @RequirePermission(Permissions.RESOURCE_CREATE_TYPE)
    public ResponseEntity<ResourceTypeView> createType(@Valid @RequestBody CreateResourceTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createType(request.name(), request.toMemberTypes()));
    }

    @DeleteMapping("/types/{id}")
    @RequirePermission(Permissions.RESOURCE_DELETE_TYPE)
    @RequireStepUp
    public ResponseEntity<Void> deleteType(@PathVariable UUID id) {
        service.deleteType(id);
        return ResponseEntity.noContent().build();
    }

    // --- Resources ---

    @GetMapping
    @RequirePermission(Permissions.RESOURCE_READ)
    public List<ResourceView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.RESOURCE_READ)
    public ResourceView get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Full detail for the scoped console: parents/children for DAG navigation + labelled members/grants. */
    @GetMapping("/{id}/detail")
    @RequirePermission(Permissions.RESOURCE_READ)
    public ResourceDetailView detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping
    @RequirePermission(Permissions.RESOURCE_CREATE)
    public ResponseEntity<ResourceView> create(@Valid @RequestBody ResourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.name(), request.typeName()));
    }

    /** Creates a sub-resource under a parent the caller manages (how a delegated admin grows their subtree). */
    @PostMapping("/{parentId}/sub-resources")
    @RequirePermission(Permissions.RESOURCE_CREATE)
    @RequireStepUp
    public ResponseEntity<ResourceView> createSubResource(@PathVariable UUID parentId,
                                                          @Valid @RequestBody ResourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createSubResource(parentId, request.name(), request.typeName()));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    public ResourceView rename(@PathVariable UUID id, @Valid @RequestBody ResourceRequest request) {
        return service.rename(id, request.name());
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.RESOURCE_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Edges ---

    @PostMapping("/{id}/children")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    @RequireStepUp
    public ResponseEntity<Void> attachChild(@PathVariable UUID id, @Valid @RequestBody ChildRequest request) {
        service.attachChild(id, request.childId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/children/{childId}")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    @RequireStepUp
    public ResponseEntity<Void> detachChild(@PathVariable UUID id, @PathVariable UUID childId) {
        service.detachChild(id, childId);
        return ResponseEntity.noContent().build();
    }

    // --- Members ---

    @PostMapping("/{id}/members")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    @RequireStepUp
    public ResourceView attachMember(@PathVariable UUID id, @Valid @RequestBody MemberRequest request) {
        return service.attachMember(id, request.toMemberType(), request.memberId());
    }

    @DeleteMapping("/{id}/members/{memberType}/{memberId}")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    @RequireStepUp
    public ResourceView detachMember(@PathVariable UUID id, @PathVariable String memberType,
                                     @PathVariable String memberId) {
        return service.detachMember(id, MemberTypes.parse(memberType), memberId);
    }

    // --- Delegation grants ---

    @PostMapping("/{id}/admins")
    @RequirePermission(Permissions.RESOURCE_ASSIGN_ADMIN)
    @RequireStepUp
    public ResourceView assignAdmin(@PathVariable UUID id, @Valid @RequestBody AdminGrantRequest request) {
        return service.assignAdmin(id, request.userId());
    }

    @DeleteMapping("/{id}/admins/{userId}")
    @RequirePermission(Permissions.RESOURCE_ASSIGN_ADMIN)
    @RequireStepUp
    public ResourceView revokeAdmin(@PathVariable UUID id, @PathVariable UUID userId) {
        return service.revokeAdmin(id, userId);
    }
}
