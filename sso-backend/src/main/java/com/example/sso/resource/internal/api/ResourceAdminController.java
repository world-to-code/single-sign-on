package com.example.sso.resource.internal.api;

import com.example.sso.audit.Audited;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.metadata.Attribute;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    @Audited(value = AuditType.RESOURCE_CHANGED)
    @PostMapping("/types")
    @RequirePermission(Permissions.RESOURCE_CREATE_TYPE)
    public ResponseEntity<ResourceTypeView> createType(@Valid @RequestBody CreateResourceTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createType(request.name(), request.toMemberTypes()));
    }

    @Audited(value = AuditType.RESOURCE_CHANGED)
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

    @Audited(value = AuditType.RESOURCE_CHANGED)
    @PostMapping
    @RequirePermission(Permissions.RESOURCE_CREATE)
    public ResponseEntity<ResourceView> create(@Valid @RequestBody ResourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.name(), request.typeName()));
    }

    /** Creates a sub-resource under a parent the caller manages (how a delegated admin grows their subtree). */
    @Audited(value = AuditType.RESOURCE_CHANGED)
    @PostMapping("/{parentId}/sub-resources")
    @RequirePermission(Permissions.RESOURCE_CREATE)
    @RequireStepUp
    public ResponseEntity<ResourceView> createSubResource(@PathVariable UUID parentId,
                                                          @Valid @RequestBody ResourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createSubResource(parentId, request.name(), request.typeName()));
    }

    @Audited(value = AuditType.RESOURCE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @PutMapping("/{id}")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    public ResourceView rename(@PathVariable UUID id, @Valid @RequestBody ResourceRequest request) {
        return service.rename(id, request.name());
    }

    @Audited(value = AuditType.RESOURCE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.RESOURCE_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Edges ---

    @Audited(value = AuditType.RESOURCE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @PostMapping("/{id}/children")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    @RequireStepUp
    public ResponseEntity<Void> attachChild(@PathVariable UUID id, @Valid @RequestBody ChildRequest request) {
        service.attachChild(id, request.childId());
        return ResponseEntity.noContent().build();
    }

    @Audited(value = AuditType.RESOURCE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @DeleteMapping("/{id}/children/{childId}")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    @RequireStepUp
    public ResponseEntity<Void> detachChild(@PathVariable UUID id, @PathVariable UUID childId) {
        service.detachChild(id, childId);
        return ResponseEntity.noContent().build();
    }

    // --- Members ---

    @Audited(value = AuditType.RESOURCE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @PostMapping("/{id}/members")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    @RequireStepUp
    public ResourceView attachMember(@PathVariable UUID id, @Valid @RequestBody MemberRequest request) {
        return service.attachMember(id, request.toMemberType(), request.memberId());
    }

    @Audited(value = AuditType.RESOURCE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @DeleteMapping("/{id}/members/{memberType}/{memberId}")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    @RequireStepUp
    public ResourceView detachMember(@PathVariable UUID id, @PathVariable String memberType,
                                     @PathVariable String memberId) {
        return service.detachMember(id, MemberTypes.parse(memberType), memberId);
    }

    // --- Delegation grants ---

    @Audited(value = AuditType.RESOURCE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @PostMapping("/{id}/admins")
    @RequirePermission(Permissions.RESOURCE_ASSIGN_ADMIN)
    @RequireStepUp
    public ResourceView assignAdmin(@PathVariable UUID id, @Valid @RequestBody AdminGrantRequest request) {
        return service.assignAdmin(id, request.userId());
    }

    @Audited(value = AuditType.RESOURCE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @DeleteMapping("/{id}/admins/{userId}")
    @RequirePermission(Permissions.RESOURCE_ASSIGN_ADMIN)
    @RequireStepUp
    public ResourceView revokeAdmin(@PathVariable UUID id, @PathVariable UUID userId) {
        return service.revokeAdmin(id, userId);
    }

    // --- Metadata (key/value attributes); scope-gated by the service's requireManage ---

    @GetMapping("/{id}/metadata")
    @RequirePermission(Permissions.RESOURCE_READ)
    public List<Attribute> metadata(@PathVariable UUID id) {
        return service.attributesOf(id);
    }

    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @PutMapping("/{id}/metadata")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    public List<Attribute> addMetadata(@PathVariable UUID id, @Valid @RequestBody ResourceAttributeRequest request) {
        return service.addAttribute(id, request.key(), request.value());
    }

    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @DeleteMapping("/{id}/metadata/{key}")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    public List<Attribute> removeMetadata(@PathVariable UUID id, @PathVariable String key) {
        return service.removeAttribute(id, key);
    }

    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.RESOURCE, subjectParam = "id")
    @DeleteMapping(value = "/{id}/metadata/{key}", params = "value")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    public List<Attribute> removeMetadataValue(@PathVariable UUID id, @PathVariable String key,
            @RequestParam String value) {
        return service.removeAttributeValue(id, key, value);
    }
}
