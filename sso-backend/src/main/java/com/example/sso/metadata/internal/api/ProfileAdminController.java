package com.example.sso.metadata.internal.api;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The acting tenant's profiles and the attributes each declares. A profile id is client-supplied, so the
 * service checks it belongs to the caller's organization before reading or writing through it.
 */
@RestController
@RequestMapping("/api/admin/profiles")
@RequiredArgsConstructor
public class ProfileAdminController {

    private final ProfileService profiles;
    private final AttributeDefinitionService definitions;

    @GetMapping
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_READ)
    public List<Profile> list() {
        return profiles.list();
    }

    @GetMapping("/{id}/attributes")
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_READ)
    public List<AttributeDefinition> attributes(@PathVariable UUID id) {
        return definitions.definitionsIn(id);
    }

    /** Upsert by key: the key is the identity within a profile, so re-declaring one redefines it in place. */
    @PostMapping("/{id}/attributes")
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_WRITE)
    public AttributeDefinition saveAttribute(@PathVariable UUID id,
            @Valid @RequestBody AttributeDefinitionRequest request) {
        return definitions.save(id, request.toSpec());
    }
}
