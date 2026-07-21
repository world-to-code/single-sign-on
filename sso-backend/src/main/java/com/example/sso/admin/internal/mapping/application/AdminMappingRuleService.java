package com.example.sso.admin.internal.mapping.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingRuleView;
import com.example.sso.shared.error.ForbiddenException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Applies the actor's group-access scope to the mapping-rule admin API, symmetrically with the create/update
 * gate: a rule may only be read, listed or deleted by an actor who can manage its TARGET group
 * ({@code canAccessGroup}). So a scoped delegate cannot enumerate or delete rules for groups outside its reach,
 * matching how {@code GroupAdminService} scopes group operations. Delegates the actual work to the domain
 * {@link MappingRuleService} (which enforces tenant-tier ownership).
 */
@Service
@RequiredArgsConstructor
public class AdminMappingRuleService {

    private final MappingRuleService mappingRules;
    private final AdminAccessPolicy accessPolicy;

    public List<MappingRuleView> list() {
        return mappingRules.list().stream().filter(this::mayAccess).toList();
    }

    public MappingRuleView get(UUID id) {
        MappingRuleView view = mappingRules.get(id);
        requireAccess(view);
        return view;
    }

    public MappingRuleView create(MappingRuleSpec spec) {
        return mappingRules.create(spec);
    }

    public MappingRuleView update(UUID id, MappingRuleSpec spec) {
        return mappingRules.update(id, spec);
    }

    public void delete(UUID id) {
        requireAccess(mappingRules.get(id)); // may only delete a rule whose target group the actor manages
        mappingRules.delete(id);
    }

    public Set<UUID> preview(MappingRuleSpec spec) {
        return mappingRules.preview(spec);
    }

    private boolean mayAccess(MappingRuleView view) {
        return accessPolicy.mayAssignTarget(view.thenKind(), UUID.fromString(view.targetId()));
    }

    private void requireAccess(MappingRuleView view) {
        if (!mayAccess(view)) {
            throw ForbiddenException.of("admin.mapping.targetOutsideScope");
        }
    }
}
