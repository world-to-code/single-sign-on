package com.example.sso.admin.internal.mapping.api;

import com.example.sso.admin.internal.mapping.application.AdminMappingRuleService;
import com.example.sso.mapping.MappingRuleView;
import com.example.sso.shared.IdName;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

/**
 * Admin API for metadata-driven mapping rules (predicate → group or role). Every operation requires that the
 * actor may grant the rule's TARGET by hand ({@code mayAssignTarget} — {@code canAccessGroup} for a group, the
 * role dominance / grant-only-what-you-hold check for a role): create/update/preview via a method-security
 * expression on the request's target, and read/list/delete via {@link AdminMappingRuleService} on the stored
 * rule's target — so a rule can never grant (or be stripped from) a target the actor could not manage by hand.
 * Reads also carry {@code mapping-rule:read}; mutations their own mutating permission plus step-up.
 */
@RestController
@RequestMapping("/api/admin/mapping-rules")
@RequiredArgsConstructor
public class AdminMappingRuleController {

    /** How many matched users the dry-run returns in its sample (the count is exact regardless). */
    private static final int PREVIEW_SAMPLE_CAP = 50;

    private final AdminMappingRuleService mappingRules;
    private final UserService users;

    @GetMapping
    @RequirePermission(Permissions.MAPPING_RULE_READ)
    public List<MappingRuleView> list() {
        return mappingRules.list();
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.MAPPING_RULE_READ)
    public MappingRuleView get(@PathVariable UUID id) {
        return mappingRules.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.MAPPING_RULE_CREATE
            + "') and @adminAccessPolicy.mayAssignTarget(#request.thenKind(), #request.targetId())")
    @RequireStepUp
    public ResponseEntity<MappingRuleView> create(@Valid @RequestBody MappingRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mappingRules.create(request.toSpec()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MAPPING_RULE_UPDATE
            + "') and @adminAccessPolicy.mayAssignTarget(#request.thenKind(), #request.targetId())")
    @RequireStepUp
    public MappingRuleView update(@PathVariable UUID id, @Valid @RequestBody MappingRuleRequest request) {
        return mappingRules.update(id, request.toSpec());
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.MAPPING_RULE_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        mappingRules.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('" + Permissions.MAPPING_RULE_CREATE
            + "') and @adminAccessPolicy.mayAssignTarget(#request.thenKind(), #request.targetId())")
    public MappingPreviewView preview(@Valid @RequestBody MappingRuleRequest request) {
        Set<UUID> matched = mappingRules.preview(request.toSpec());
        List<UUID> sampleIds = matched.stream().limit(PREVIEW_SAMPLE_CAP).toList();
        List<MatchedUser> sample = users.idNames(sampleIds).stream()
                .map(idName -> new MatchedUser(idName.getId().toString(), idName.getName())).toList();
        return new MappingPreviewView(matched.size(), sample);
    }
}
