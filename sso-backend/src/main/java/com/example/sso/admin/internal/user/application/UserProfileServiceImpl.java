package com.example.sso.admin.internal.user.application;

import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving a user from one profile to another.
 *
 * <p>The move is destructive by design: a profile decides which attributes a person HAS, so anything the
 * target does not declare stops existing. That is why {@link #preview} is separate — those keys can be
 * conditions on mapping rules and policy bindings, so deleting them can retract a role or change which policy
 * governs the user, and an administrator should see that coming rather than discover it afterwards.
 */
@Service
@RequiredArgsConstructor
class UserProfileServiceImpl implements UserProfileService {

    private final UserService users;
    private final ProfileService profiles;
    private final AttributeDefinitionService definitions;
    private final AttributeService attributes;
    private final ApplicationEventPublisher events;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public ProfileSwitchPreview preview(UUID userId, UUID profileId) {
        UserAccount user = requireUser(userId);
        List<String> removed = removedKeys(user, requireProfile(profileId));
        return new ProfileSwitchPreview(removed, notRemovable(removed), user.getExternalId() != null);
    }

    @Override
    @Transactional
    public void switchTo(UUID userId, UUID profileId, Collection<String> confirmedKeys) {
        UserAccount user = requireUser(userId);
        UUID target = requireProfile(profileId);
        requireLocallyManaged(user);

        List<String> removed = removedKeys(user, target);
        // Refuse before deleting anything. attributes.remove would throw on the first directory-owned key,
        // rolling the whole switch back — after preview had already told the administrator those keys would
        // simply go. Fail up front, naming them, so the two agree.
        List<String> blocked = notRemovable(removed);
        if (!blocked.isEmpty()) {
            throw ConflictException.of("metadata.profile.switchBlocked", String.join(", ", blocked));
        }
        requireStillTheConfirmedCost(removed, confirmedKeys);
        if (removed.isEmpty() && target.equals(user.getProfileId())) {
            return; // nothing to delete and nowhere to move: not a write, so not a termination either
        }
        attributes.removeAll(EntityKind.USER, userId.toString(), removed);
        users.assignProfile(userId, target);
        // The deletion can retract a role, so it has to be attributable afterwards — the keys, not the values.
        // Published rather than recorded inline: AuditService writes REQUIRES_NEW, so an inline record would
        // commit independently and then assert a deletion that a rollback undid.
        events.publishEvent(new ProfileSwitched(actingAdministrator(), user.getId(), user.getUsername(),
                user.getOrgId(), target, removed));
        if (removed.isEmpty()) {
            return;
        }
        // Own the termination rather than leaning on the async mapping re-evaluation the attribute deletions
        // also trigger. That path covers a key used by a mapping RULE, but not one used only by a policy
        // binding, and when it fails the retraction waits out the sweep interval — or is lost entirely, since
        // the sweeper does not re-drive a retraction whose claim row is already gone.
        //
        // What this does NOT do is make the role retraction itself immediate: the mapping re-evaluation is
        // @Async, so between this commit and its completion the sessions are gone while `app_user_role` still
        // carries the role, and a re-login inside that window is fully privileged. The termination is the
        // policy-binding half taking effect at once, plus a second termination when the retraction lands —
        // not the retraction. Closing the window means retracting synchronously here, which would put a
        // mapping re-evaluation inside an admin write transaction; that trade has not been made.
        //
        // Only when a key actually went, though: a move that deletes nothing changes no authorization, and an
        // unconditional termination made this endpoint a way to log a person out at will — one that answers to
        // user:update rather than to the session-revocation gate.
        events.publishEvent(new UserAccessChangedEvent(user.getUsername(), user.getOrgId()));
    }

    /**
     * The move deletes what the administrator was SHOWN, or it does not happen.
     *
     * <p>Preview and confirm are two requests with nothing binding them. Between them a sync can add an
     * attribute, or the target profile can drop a declaration — and the extra key that then goes can be the
     * condition on a mapping rule or a policy binding, so the move retracts a role nobody agreed to lose.
     * Refusing sends the console back to preview, which is cheap; the alternative is not undoable.
     */
    private void requireStillTheConfirmedCost(List<String> removed, Collection<String> confirmedKeys) {
        if (confirmedKeys == null) {
            return; // a caller that never previewed accepts the computed cost
        }
        if (!Set.copyOf(removed).equals(Set.copyOf(confirmedKeys))) {
            throw ConflictException.of("metadata.profile.previewStale");
        }
    }

    /**
     * Who is performing the move, read while still on the request thread — the AFTER_COMMIT listener that
     * writes the audit row may not have a security context, and a later @Async move would certainly not.
     */
    private String actingAdministrator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    /**
     * The keys the move would have to delete but this administrator may not — asked of the store that will do
     * the deleting, rather than re-derived here.
     *
     * <p>This used to test one reason (a directory owns the key) and the write enforced three, so each reason
     * the preview did not know about surfaced as a confirm that failed on something never disclosed. Asking
     * the store means a reason added there is reported here without anyone remembering to.
     */
    private List<String> notRemovable(List<String> keys) {
        return List.copyOf(attributes.keysNotRemovable(EntityKind.USER, keys));
    }

    /** Attributes the user carries that the target profile does not declare. */
    private List<String> removedKeys(UserAccount user, UUID profileId) {
        Set<String> declared = definitions.definitionsIn(profileId).stream()
                .map(AttributeDefinition::key).collect(Collectors.toSet());
        return attributes.attributesOfInTier(EntityKind.USER, user.getId().toString()).stream()
                .map(Attribute::key)
                .distinct()
                // `declared` already carries the base attributes — definitionsIn synthesises them — so a stray
                // tag named after an app_user column is excluded by the same test as everything else. One
                // mechanism, not two that could disagree.
                .filter(key -> !declared.contains(key))
                .sorted()
                .toList();
    }

    /**
     * A directory or SCIM owns an externally-provisioned user's attributes, so moving them would only start a
     * fight the next sync wins — while having deleted the values in the meantime.
     */
    private void requireLocallyManaged(UserAccount user) {
        if (user.getExternalId() != null) {
            throw ConflictException.of("metadata.profile.externallyManaged");
        }
    }

    /**
     * The subject, only if they belong to the acting organization.
     *
     * <p>{@code app_user} carries no RLS — users are global identities — so nothing below this service scopes
     * a user id. Without this check an administrator of one tenant could bind another tenant's person (or a
     * platform account, crossing the tier without drilling in) to their own schema. Non-revealing on purpose:
     * a foreign id is indistinguishable from one that does not exist.
     */
    private UserAccount requireUser(UUID userId) {
        UserAccount user = users.findById(userId)
                .filter(candidate -> Objects.equals(users.orgIdOf(userId).orElse(null),
                        orgContext.currentOrg().orElse(null)))
                .orElseThrow(() -> NotFoundException.of("user.notFound"));
        return user;
    }

    /** The target, resolved by the one check every profile-binding route shares. */
    private UUID requireProfile(UUID profileId) {
        return profiles.requireAssignable(profileId).id();
    }
}
