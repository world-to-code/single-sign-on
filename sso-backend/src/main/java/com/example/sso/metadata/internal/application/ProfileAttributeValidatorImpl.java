package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default {@link ProfileAttributeValidator}. */
@Service
@RequiredArgsConstructor
class ProfileAttributeValidatorImpl implements ProfileAttributeValidator {

    private final AttributeDefinitionService definitions;
    private final ProfileService profiles;

    @Override
    @Transactional(readOnly = true)
    public void validate(UUID profileId, Map<String, ? extends Collection<String>> values) {
        validate(definitions.definitionsIn(profileId), values);
    }

    @Override
    public void validate(List<AttributeDefinition> declaredIn, Map<String, ? extends Collection<String>> values) {
        Map<String, AttributeDefinition> declared = declaredIn.stream()
                // Base attributes are app_user columns written through their own fields, not as attributes.
                .filter(definition -> !definition.base())
                .collect(Collectors.toMap(AttributeDefinition::key, Function.identity()));

        values.forEach((key, given) -> {
            AttributeDefinition definition = declared.get(key);
            if (definition == null) {
                // Refuse rather than store: an undeclared key is a typo that becomes data nobody can find.
                throw BadRequestException.of("metadata.attribute.undeclared", key);
            }
            if (!definition.locallyEditable()) {
                // A directory owns this one, so the sync would overwrite whatever an administrator typed. The
                // store refuses it downstream; catching it here is what makes "validate before creating"
                // true — otherwise validation passes and the account is half-made when add() throws.
                throw BadRequestException.of("metadata.attribute.directoryOwned", definition.displayName());
            }
            check(definition, given);
        });

        declared.values().stream()
                .filter(AttributeDefinition::required)
                // A required attribute a directory fills is not the administrator's to supply.
                .filter(AttributeDefinition::locallyEditable)
                .filter(definition -> isBlank(values.get(definition.key())))
                .findFirst()
                .ifPresent(definition -> {
                    throw BadRequestException.of("metadata.attribute.required", definition.displayName());
                });
    }

    @Override
    @Transactional(readOnly = true)
    public UUID defaultForCreation() {
        List<Profile> own = profiles.list();
        return own.stream().filter(Profile::defaultForCreation).findFirst()
                .or(() -> own.stream().filter(Profile::system).findFirst())
                .map(Profile::id)
                .orElseThrow(() -> new NotFoundException("Profile not found"));
    }

    private void check(AttributeDefinition definition, Collection<String> given) {
        if (given.size() > 1 && !definition.multiValued()) {
            throw BadRequestException.of("metadata.attribute.singleValued", definition.displayName());
        }
        given.stream().filter(value -> !fits(definition, value)).findFirst().ifPresent(value -> {
            throw BadRequestException.of("metadata.attribute.invalid", definition.displayName());
        });
    }

    /** The declared type is a rendering and validation contract; storage stays text (see V121). */
    private boolean fits(AttributeDefinition definition, String value) {
        if (value == null || value.isBlank()) {
            return true; // absent is handled by the required check, not here
        }
        return switch (definition.dataType()) {
            case STRING -> true;
            case INTEGER -> value.trim().matches("-?\\d+");
            case BOOLEAN -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
            case DATE -> isDate(value.trim());
            case ENUM -> definition.enumValues().contains(value);
        };
    }

    private boolean isDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException notADate) {
            return false;
        }
    }

    private boolean isBlank(Collection<String> given) {
        return given == null || given.isEmpty() || given.stream().allMatch(v -> v == null || v.isBlank());
    }
}
