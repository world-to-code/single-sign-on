package com.example.sso.resource.internal.application;

import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the baseline resource-type vocabulary. Types are GLOBAL and mintable only by a platform
 * super-admin, so without a seeded vocabulary a tenant admin — who owns their org's resource TREE — could
 * not create a single resource. These baseline types give every tenant an organizational hierarchy out of
 * the box; a platform admin may still add more. Idempotent (get-or-create by name).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResourceTypeSeeder implements ApplicationRunner {

    /** What each baseline type may contain: sub-resources plus the subjects a node grants access to. */
    private static final Map<String, Set<MemberType>> BASELINE_TYPES = Map.of(
            "BRANCH", Set.of(MemberType.RESOURCE, MemberType.GROUP, MemberType.APPLICATION, MemberType.USER),
            "DEPARTMENT", Set.of(MemberType.RESOURCE, MemberType.GROUP, MemberType.APPLICATION, MemberType.USER),
            "TEAM", Set.of(MemberType.GROUP, MemberType.APPLICATION, MemberType.USER));

    private final ResourceTypeRepository types;
    private final ResourceTypeAllowedMemberRepository allowedMembers;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        BASELINE_TYPES.forEach(this::seedType);
    }

    private void seedType(String name, Set<MemberType> allowed) {
        if (types.findByName(name).isPresent()) {
            return;
        }
        UUID typeId = types.save(new ResourceType(name)).getId();
        allowed.forEach(memberType -> allowedMembers.save(new ResourceTypeAllowedMember(typeId, memberType)));
        log.info("Seeded baseline resource type '{}'.", name);
    }
}
