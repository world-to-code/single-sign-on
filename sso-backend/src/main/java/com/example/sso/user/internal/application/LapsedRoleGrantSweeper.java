package com.example.sso.user.internal.application;

import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.role.RoleGrantExpiredEvent;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import com.example.sso.user.internal.role.domain.UserRole;
import com.example.sso.user.internal.role.domain.UserRoleRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes away role grants whose time is up, and ENDS THE SESSIONS THEY AUTHORIZED.
 *
 * <p>The second half is the point. Reads that decide authorization already ignore a lapsed grant, so an
 * expiry is respected the moment anything asks — but a session established while the grant was live is
 * carrying the authority with it, and nothing asks again until it ends. Letting that session run to its own
 * expiry is the same mistake as deleting a password hash and calling it revocation: the grant is gone and the
 * access is not. So a lapse takes the established route, {@link UserAccessChangedEvent} → session termination
 * → back-channel logout / SAML SLO.
 *
 * <p>Removing the row is what makes this idempotent: a grant is swept once, and the next pass has nothing to
 * find. It also keeps the expiry from becoming an archaeological record that every future query has to
 * remember to filter — the reason the row is gone is that it no longer grants anything.
 *
 * <p>A failure leaves the row in place for the next pass rather than losing it, and is logged: a sweeper that
 * has been failing quietly is a set of privileges that expired only on paper.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class LapsedRoleGrantSweeper {

    private final UserRoleRepository userRoles;
    private final AppUserRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${sso.rbac.grant-expiry.sweep-interval}")
    void sweep() {
        try {
            removeLapsedGrants();
        } catch (RuntimeException e) {
            log.error("Lapsed role-grant sweep failed; the grants stay until the next pass", e);
        }
    }

    @Transactional
    void removeLapsedGrants() {
        List<UserRole> lapsed = userRoles.findLapsedBy(clock.instant());
        if (lapsed.isEmpty()) {
            return;
        }

        Map<UUID, AppUser> holders = users.findAllById(lapsed.stream().map(UserRole::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));

        for (UserRole grant : lapsed) {
            userRoles.deleteByUserIdAndRoleId(grant.getUserId(), grant.getRoleId());
            // A holder that no longer exists needs no session ended and no audit actor invented; the row is
            // still removed, because the grant it represents is over either way.
            Optional.ofNullable(holders.get(grant.getUserId())).ifPresent(holder -> endAccess(holder, grant));
        }
        log.info("Removed {} lapsed role grant(s)", lapsed.size());
    }

    private void endAccess(AppUser holder, UserRole grant) {
        // Two separate facts, and they are not interchangeable: one ENDS the access, the other RECORDS that it
        // ended. The record goes out as an event because the audit module already depends on this one to
        // resolve actors, and calling it directly would close a cycle.
        events.publishEvent(new UserAccessChangedEvent(holder.getUsername(), holder.getOrgId()));
        events.publishEvent(new RoleGrantExpiredEvent(holder.getUsername(), holder.getOrgId(), grant.getRoleId()));
    }
}
