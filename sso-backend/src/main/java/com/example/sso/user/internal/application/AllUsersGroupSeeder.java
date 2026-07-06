package com.example.sso.user.internal.application;

import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.internal.domain.UserGroup;
import com.example.sso.user.internal.domain.UserGroupMember;
import com.example.sso.user.internal.domain.UserGroupMemberRepository;
import com.example.sso.user.internal.domain.UserGroupRepository;
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
 * Ensures the platform-managed "All Users" group exists and contains every user. Created once, then
 * backfilled idempotently on each boot (missing members are added as explicit {@code user_group_member}
 * rows). New users join via {@link UserService#createUser} at creation time.
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
        users.findAll().stream().map(AppUser::getId)
                .filter(id -> !current.contains(id))
                .forEach(id -> members.save(new UserGroupMember(saved.getId(), id)));
    }
}
