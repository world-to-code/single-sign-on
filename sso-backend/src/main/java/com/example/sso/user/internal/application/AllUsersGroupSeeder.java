package com.example.sso.user.internal.application;

import com.example.sso.user.account.UserService;

import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import com.example.sso.user.internal.group.domain.UserGroup;
import com.example.sso.user.internal.group.domain.UserGroupMember;
import com.example.sso.user.internal.group.domain.UserGroupMemberRepository;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures the GLOBAL "All Users" group exists and contains every GLOBAL (org-less) user — the platform
 * super-admins. Created once, then backfilled idempotently on each boot. Tenant users belong to their OWN
 * org's "All Users" group (per-tenant, created at {@link UserService#createUser} time), never this global
 * one — so a global group assignment can never cross the tenant boundary. Backfilling tenant users here
 * would re-introduce exactly that leak, so the backfill is restricted to org-less accounts.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class AllUsersGroupSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AllUsersGroupSeeder.class);

    private final UserGroupRepository groups;
    private final UserGroupMemberRepository members;
    private final AppUserRepository users;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UserGroup group = groups.findByNameAndOrgIdIsNull(UserGroup.ALL_USERS).orElseGet(() -> {
            log.info("Seeded system group '{}'.", UserGroup.ALL_USERS);
            return new UserGroup(UserGroup.ALL_USERS, "Every user belongs to this group.", null);
        });
        if (!group.isSystem()) {
            group.markSystem();
        }
        UserGroup saved = groups.save(group); // persist (and assign an id for a brand-new group)

        Set<UUID> current = new HashSet<>(members.findUserIdsByGroupId(saved.getId()));
        users.findAll().stream()
                .filter(user -> user.getOrgId() == null) // tenant users belong to their own org's group, not this
                .map(AppUser::getId)
                .filter(id -> !current.contains(id))
                .forEach(id -> members.save(new UserGroupMember(saved.getId(), id)));
    }
}
