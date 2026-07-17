package com.example.sso.admin.internal.metadata.api;

import com.example.sso.admin.internal.shared.security.CanViewUser;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.Audited;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin editing of USER and GROUP metadata attributes. A key may carry SEVERAL values (multi-value): PUT ADDS a
 * value (idempotent), DELETE {@code .../{key}} removes ALL of the key's values, and DELETE {@code .../{key}?value=}
 * removes one value. Each handler carries the SAME instance-level scope check as the entity's own endpoints
 * ({@code canAccessUser}/{@code canAccessGroup}), not just the permission, so a subtree-scoped delegated admin can
 * only tag entities it can already see/manage. RESOURCE metadata lives on the resource module's controller
 * instead, whose tier-aware {@code requireManage} check is the right guard for it. The service org-scopes every
 * read/write.
 */
@RestController
@RequestMapping("/api/admin/metadata")
@RequiredArgsConstructor
public class MetadataAdminController {

    private final AttributeService attributes;

    // --- Users ---
    @GetMapping("/users/{id}")
    @CanViewUser
    public List<Attribute> userAttributes(@PathVariable UUID id) {
        return attributes.attributesOf(EntityKind.USER, id.toString());
    }

    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.USER, subjectParam = "id")
    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') and @adminAccessPolicy.canAccessUser(#id)")
    public List<Attribute> addUserAttribute(@PathVariable UUID id, @Valid @RequestBody AttributeRequest request) {
        attributes.add(EntityKind.USER, id.toString(), request.key(), request.value());
        return attributes.attributesOf(EntityKind.USER, id.toString());
    }

    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.USER, subjectParam = "id")
    @DeleteMapping("/users/{id}/{key}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') and @adminAccessPolicy.canAccessUser(#id)")
    public List<Attribute> removeUserAttribute(@PathVariable UUID id, @PathVariable String key) {
        attributes.remove(EntityKind.USER, id.toString(), key);
        return attributes.attributesOf(EntityKind.USER, id.toString());
    }

    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.USER, subjectParam = "id")
    @DeleteMapping(value = "/users/{id}/{key}", params = "value")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') and @adminAccessPolicy.canAccessUser(#id)")
    public List<Attribute> removeUserAttributeValue(@PathVariable UUID id, @PathVariable String key,
            @RequestParam String value) {
        attributes.removeValue(EntityKind.USER, id.toString(), key, value);
        return attributes.attributesOf(EntityKind.USER, id.toString());
    }

    // --- Groups (no @Can... annotation exists for groups; the scope check is composed inline) ---
    @GetMapping("/groups/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_READ + "') and @adminAccessPolicy.canAccessGroup(#id)")
    public List<Attribute> groupAttributes(@PathVariable UUID id) {
        return attributes.attributesOf(EntityKind.GROUP, id.toString());
    }

    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.GROUP, subjectParam = "id")
    @PutMapping("/groups/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_UPDATE + "') and @adminAccessPolicy.canAccessGroup(#id)")
    public List<Attribute> addGroupAttribute(@PathVariable UUID id, @Valid @RequestBody AttributeRequest request) {
        attributes.add(EntityKind.GROUP, id.toString(), request.key(), request.value());
        return attributes.attributesOf(EntityKind.GROUP, id.toString());
    }

    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.GROUP, subjectParam = "id")
    @DeleteMapping("/groups/{id}/{key}")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_UPDATE + "') and @adminAccessPolicy.canAccessGroup(#id)")
    public List<Attribute> removeGroupAttribute(@PathVariable UUID id, @PathVariable String key) {
        attributes.remove(EntityKind.GROUP, id.toString(), key);
        return attributes.attributesOf(EntityKind.GROUP, id.toString());
    }

    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.GROUP, subjectParam = "id")
    @DeleteMapping(value = "/groups/{id}/{key}", params = "value")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_UPDATE + "') and @adminAccessPolicy.canAccessGroup(#id)")
    public List<Attribute> removeGroupAttributeValue(@PathVariable UUID id, @PathVariable String key,
            @RequestParam String value) {
        attributes.removeValue(EntityKind.GROUP, id.toString(), key, value);
        return attributes.attributesOf(EntityKind.GROUP, id.toString());
    }
}
