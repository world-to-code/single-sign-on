package com.example.sso.metadata.internal.api;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.Audited;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The acting tenant's profile schema. Deleting a definition is step-up gated, per the step-up rule's "all
 * *:delete operations": it destroys a catalog entry that mapping rules and policy bindings match on, so it
 * destroys data even though the definition itself carries no credential. Create/redefine are not gated —
 * they are not in the rule's list and expand no authority. Tier scoping is enforced in the service, which
 * fails closed for a bound-but-orgless caller.
 */
@RestController
@RequestMapping("/api/admin/attribute-definitions")
@RequiredArgsConstructor
public class AttributeDefinitionAdminController {

    private final AttributeDefinitionService service;

    @GetMapping
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_READ)
    public List<AttributeDefinition> list(@RequestParam(defaultValue = "USER") EntityKind entityKind) {
        return service.definitionsFor(entityKind);
    }

    /** Upsert by (kind, key): the key is the identity, so re-declaring one redefines it in place. */
    @Audited(value = AuditType.ATTRIBUTE_DEFINITION_CHANGED)
    @PostMapping
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_WRITE)
    public AttributeDefinition save(@Valid @RequestBody AttributeDefinitionRequest request) {
        return service.save(request.toSpec());
    }

    @Audited(value = AuditType.ATTRIBUTE_DEFINITION_CHANGED)
    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_WRITE)
    @RequireStepUp
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
