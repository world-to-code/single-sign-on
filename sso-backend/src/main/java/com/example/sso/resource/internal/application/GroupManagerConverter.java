package com.example.sso.resource.internal.application;

import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceGrant;
import com.example.sso.resource.internal.domain.ResourceMember;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.user.GroupManagers;
import com.example.sso.user.UserGroupService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time migration of the legacy group-manager mechanism to resource ADMIN grants: for each group
 * with managers, a bridging resource (type {@code MANAGED_GROUP}) that has the group as a member, with
 * each manager granted ADMIN. That grant confers exactly the old reach (the group ∈ the manager's
 * scoped groups, its members ∈ their scoped users). Runs idempotently on every boot — a group that
 * already has a bridging resource is skipped — so it is safe to keep until {@code user_group_manager}
 * is dropped.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GroupManagerConverter {

    public static final String TYPE_NAME = "MANAGED_GROUP";

    private final UserGroupService userGroups;
    private final ResourceRepository resources;
    private final ResourceTypeRepository types;

    @EventListener(ApplicationReadyEvent.class)
    void convertOnStartup() {
        int created = convert();
        if (created > 0) {
            log.info("Migrated {} managed group(s) to resource ADMIN grants", created);
        }
    }

    @Transactional
    public int convert() {
        var managed = userGroups.groupsWithManagers();
        if (managed.isEmpty()) {
            return 0;
        }

        ResourceType type = ensureType();
        int created = 0;
        for (GroupManagers group : managed) {
            if (resources.existsGroupResourceOfType(TYPE_NAME, group.groupId().toString())) {
                continue;
            }
            Resource bridge = new Resource(group.groupName(), type);
            group.managerIds().forEach(manager -> bridge.grant(ResourceGrant.admin(manager)));
            bridge.attachMember(ResourceMember.group(group.groupId()));
            resources.save(bridge);
            created++;
        }
        return created;
    }

    private ResourceType ensureType() {
        return types.findByNameFetchingKinds(TYPE_NAME)
                .orElseGet(() -> types.save(new ResourceType(TYPE_NAME, Set.of(MemberType.GROUP))));
    }
}
