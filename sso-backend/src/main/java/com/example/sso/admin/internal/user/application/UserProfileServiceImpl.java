package com.example.sso.admin.internal.user.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public ProfileSwitchPreview preview(UUID userId, UUID profileId) {
        UserAccount user = requireUser(userId);
        List<String> removed = removedKeys(user, requireProfile(profileId));
        return new ProfileSwitchPreview(removed, directoryOwned(removed));
    }

    @Override
    @Transactional
    public void switchTo(UUID userId, UUID profileId) {
        UserAccount user = requireUser(userId);
        UUID target = requireProfile(profileId);
        requireLocallyManaged(user);

        List<String> removed = removedKeys(user, target);
        // Refuse before deleting anything. attributes.remove would throw on the first directory-owned key,
        // rolling the whole switch back — after preview had already told the administrator those keys would
        // simply go. Fail up front, naming them, so the two agree.
        List<String> blocked = directoryOwned(removed);
        if (!blocked.isEmpty()) {
            throw ConflictException.of("metadata.profile.switchBlocked", String.join(", ", blocked));
        }
        attributes.removeAll(EntityKind.USER, userId.toString(), removed);
        users.assignProfile(userId, target);
        // The deletion can retract a role, so it has to be attributable afterwards — the keys, not the values.
        // Published rather than recorded inline: AuditService writes REQUIRES_NEW, so an inline record would
        // commit independently and then assert a deletion that a rollback undid.
        events.publishEvent(new ProfileSwitched(user.getUsername(), user.getOrgId(), target, removed));
        // Own the termination rather than leaning on the async mapping re-evaluation the attribute deletions
        // also trigger. That path covers a key used by a mapping RULE, but not one used only by a policy
        // binding, and when it fails the retraction waits out the sweep interval — or is lost entirely, since
        // the sweeper does not re-drive a retraction whose claim row is already gone. A destructive,
        // privilege-changing operation should not depend on a side effect to take effect.
        events.publishEvent(new UserAccessChangedEvent(user.getUsername(), user.getOrgId()));
    }

    /**
     * The keys a DIRECTORY owns, which an administrator may not delete.
     *
     * <p>Ownership resolves through the TENANT profile — the same profile {@code attributes.remove} consults —
     * not through the move's target, or preview and the write would disagree again.
     */
    private List<String> directoryOwned(List<String> keys) {
        return keys.stream()
                .filter(key -> definitions.definitionOf(EntityKind.USER, key)
                        .filter(definition -> !definition.locallyEditable())
                        .isPresent())
                .toList();
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

    /**
     * The target, which must be one of the tenant's OWN profiles.
     *
     * <p>A source profile describes what a directory provides, not what a person is, and it dies with its
     * connector — {@code profile.connector_id} cascades, and {@code app_user.profile_id} is ON DELETE SET
     * NULL, so deleting a connector would silently reset every bound user's schema with nothing recorded.
     * The mapping side already refuses a non-tenant target for a related reason.
     */
    private UUID requireProfile(UUID profileId) {
        Profile profile = profiles.findById(profileId)
                .orElseThrow(() -> NotFoundException.of("metadata.profile.notFound"));
        if (profile.kind() != ProfileKind.TENANT) {
            throw BadRequestException.of("metadata.profile.notAssignable");
        }
        return profile.id();
    }
}
