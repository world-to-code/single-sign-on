package com.example.sso.admin.internal.user.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves a user's profile columns as ONE set, which is the only shape that can enforce what the profile
 * declares.
 *
 * <p>The per-key metadata route cannot: a write naming one attribute has no way to tell an attribute the
 * profile REQUIRES and nobody filled from one this request simply does not mention. So the create form's rules
 * held at creation and nowhere afterwards — an administrator could delete a required column's only value and
 * leave an account the profile says is invalid. Taking the whole map restores the symmetry: the same
 * {@link ProfileAttributeValidator} that guards creation guards every later edit.
 *
 * <p>Only LOCAL columns are written. A DIRECTORY-owned column belongs to its connector, and the validator
 * refuses it explicitly rather than letting a console edit be silently overwritten by the next sync.
 */
@Service
@RequiredArgsConstructor
public class UserProfileAttributeService {

    private final UserService users;
    private final ProfileService profiles;
    private final AttributeDefinitionService definitions;
    private final ProfileAttributeValidator validator;
    private final AttributeService attributes;

    /** The columns this user's profile declares, base first, in the profile's own order. */
    @Transactional(readOnly = true)
    public List<AttributeDefinition> columnsOf(UUID userId) {
        return definitions.definitionsIn(profileOf(userId));
    }

    /**
     * Replaces every LOCALLY-owned column this user's profile declares with {@code values}.
     *
     * <p>A REPLACE, not a merge: a column the request omits is cleared, which is what makes "unset this
     * optional attribute" expressible at all — a merge would give the form no way to say it. That also means
     * the request has to carry the whole set, so the required check below is asking about the state the user
     * is left in rather than about this request's vocabulary.
     */
    @Transactional
    public void replace(UUID userId, Map<String, List<String>> values) {
        UUID profileId = profileOf(userId);
        validator.validate(profileId, values);

        Set<String> writable = definitions.definitionsIn(profileId).stream()
                .filter(definition -> !definition.base())
                .filter(AttributeDefinition::locallyEditable)
                .map(AttributeDefinition::key)
                .collect(Collectors.toSet());

        String entityId = userId.toString();
        // Clear first, then write: a column dropped from the map has to lose its old values, and clearing only
        // what the profile declares leaves a directory's columns — and anything an older profile left behind —
        // untouched rather than silently reaped by an unrelated edit.
        List<String> cleared = new ArrayList<>(writable);
        attributes.removeAll(EntityKind.USER, entityId, cleared);
        attributes.addAll(EntityKind.USER, entityId, nonBlank(values, writable));
    }

    /** The values actually worth storing: declared, writable, and not blank. */
    private Map<String, List<String>> nonBlank(Map<String, List<String>> values, Set<String> writable) {
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            if (!writable.contains(entry.getKey())) {
                continue;
            }
            List<String> usable = usableValues(entry.getValue());
            if (!usable.isEmpty()) {
                kept.put(entry.getKey(), usable);
            }
        }
        return kept;
    }

    private List<String> usableValues(Collection<String> given) {
        return given == null ? List.of()
                : given.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }

    /** This user's profile, falling back to the organization's own when the account names none. */
    private UUID profileOf(UUID userId) {
        UserAccount user = users.findById(userId).orElseThrow(() -> NotFoundException.of("user.notFound"));
        if (user.getProfileId() != null) {
            return user.getProfileId();
        }
        return profiles.tenantProfile().map(Profile::id)
                .orElseThrow(() -> NotFoundException.of("metadata.profile.notFound"));
    }
}
