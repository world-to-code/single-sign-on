package com.example.sso.metadata.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.metadata.ProfileSwitchPreview;
import com.example.sso.metadata.UserProfileService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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

    @Override
    @Transactional(readOnly = true)
    public ProfileSwitchPreview preview(UUID userId, UUID profileId) {
        UserAccount user = requireUser(userId);
        return new ProfileSwitchPreview(removedKeys(user, requireProfile(profileId)));
    }

    @Override
    @Transactional
    public void switchTo(UUID userId, UUID profileId) {
        UserAccount user = requireUser(userId);
        UUID target = requireProfile(profileId);
        requireLocallyManaged(user);

        List<String> removed = removedKeys(user, target);
        removed.forEach(key -> attributes.remove(EntityKind.USER, userId.toString(), key));
        users.assignProfile(userId, target);
        // The deletion can retract a role, so it has to be attributable afterwards — the keys, not the values.
        audit.record(new AuditRecord(AuditType.ATTRIBUTE_CHANGED, user.getUsername(), true,
                "profile=" + target + " removed=" + String.join(",", removed), null));
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

    private UserAccount requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private UUID requireProfile(UUID profileId) {
        return profiles.findById(profileId)
                .orElseThrow(() -> new NotFoundException("Profile not found")).id();
    }
}
