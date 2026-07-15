package com.example.sso.admin.internal.metadata.api;

import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin editing of an entity's metadata attributes. Each entity kind's endpoints carry that kind's own
 * read/update permission (not a broad {@code metadata:*}), so metadata authz never widens what the caller can
 * already do to the entity; the service org-scopes every read/write. APPLICATION metadata is a follow-up (it
 * needs the oidc-client-vs-saml-rp update distinction).
 */
@RestController
@RequestMapping("/api/admin/metadata")
@RequiredArgsConstructor
public class MetadataAdminController {

    private final AttributeService attributes;

    // --- Users ---
    @GetMapping("/users/{id}")
    @RequirePermission(Permissions.USER_READ)
    public List<Attribute> userAttributes(@PathVariable UUID id) {
        return attributes.attributesOf(EntityKind.USER, id.toString());
    }

    @PutMapping("/users/{id}")
    @RequirePermission(Permissions.USER_UPDATE)
    public List<Attribute> setUserAttribute(@PathVariable UUID id, @Valid @RequestBody AttributeRequest request) {
        return upsert(EntityKind.USER, id.toString(), request);
    }

    @DeleteMapping("/users/{id}/{key}")
    @RequirePermission(Permissions.USER_UPDATE)
    public List<Attribute> removeUserAttribute(@PathVariable UUID id, @PathVariable String key) {
        return delete(EntityKind.USER, id.toString(), key);
    }

    // --- Groups ---
    @GetMapping("/groups/{id}")
    @RequirePermission(Permissions.GROUP_READ)
    public List<Attribute> groupAttributes(@PathVariable UUID id) {
        return attributes.attributesOf(EntityKind.GROUP, id.toString());
    }

    @PutMapping("/groups/{id}")
    @RequirePermission(Permissions.GROUP_UPDATE)
    public List<Attribute> setGroupAttribute(@PathVariable UUID id, @Valid @RequestBody AttributeRequest request) {
        return upsert(EntityKind.GROUP, id.toString(), request);
    }

    @DeleteMapping("/groups/{id}/{key}")
    @RequirePermission(Permissions.GROUP_UPDATE)
    public List<Attribute> removeGroupAttribute(@PathVariable UUID id, @PathVariable String key) {
        return delete(EntityKind.GROUP, id.toString(), key);
    }

    // --- Resources ---
    @GetMapping("/resources/{id}")
    @RequirePermission(Permissions.RESOURCE_READ)
    public List<Attribute> resourceAttributes(@PathVariable UUID id) {
        return attributes.attributesOf(EntityKind.RESOURCE, id.toString());
    }

    @PutMapping("/resources/{id}")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    public List<Attribute> setResourceAttribute(@PathVariable UUID id, @Valid @RequestBody AttributeRequest request) {
        return upsert(EntityKind.RESOURCE, id.toString(), request);
    }

    @DeleteMapping("/resources/{id}/{key}")
    @RequirePermission(Permissions.RESOURCE_UPDATE)
    public List<Attribute> removeResourceAttribute(@PathVariable UUID id, @PathVariable String key) {
        return delete(EntityKind.RESOURCE, id.toString(), key);
    }

    private List<Attribute> upsert(EntityKind kind, String id, AttributeRequest request) {
        attributes.set(kind, id, request.key(), request.value());
        return attributes.attributesOf(kind, id);
    }

    private List<Attribute> delete(EntityKind kind, String id, String key) {
        attributes.remove(kind, id, key);
        return attributes.attributesOf(kind, id);
    }
}
