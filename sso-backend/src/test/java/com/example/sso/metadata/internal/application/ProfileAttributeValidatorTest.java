package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.BaseAttributes;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A profile's declarations only mean something if the server enforces them. Without this the console would
 * render a required field and the API would happily accept a create that omitted it, and a typo'd key would
 * become data nobody can find.
 */
class ProfileAttributeValidatorTest {

    private static final UUID PROFILE = UUID.randomUUID();

    private final AttributeDefinitionService definitions = mock(AttributeDefinitionService.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final ProfileAttributeValidator validator =
            new ProfileAttributeValidatorImpl(definitions, profiles);

    private AttributeDefinition definition(String key, AttributeDataType type, List<String> enumValues,
            boolean multiValued, boolean required) {
        return new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, key, key, null, type, enumValues,
                multiValued, required, AttributeSource.LOCAL, 0);
    }

    private void declares(AttributeDefinition... definitions0) {
        when(definitions.definitionsIn(any())).thenReturn(List.of(definitions0));
    }

    @Test
    void acceptsValuesThatFitWhatTheProfileDeclares() {
        declares(definition("team", AttributeDataType.STRING, List.of(), false, false),
                definition("headcount", AttributeDataType.INTEGER, List.of(), false, false));

        assertThatCode(() -> validator.validate(PROFILE,
                Map.of("team", List.of("Platform"), "headcount", List.of("12")))).doesNotThrowAnyException();
    }

    @Test
    void refusesAKeyTheProfileDoesNotDeclare() {
        declares(definition("team", AttributeDataType.STRING, List.of(), false, false));

        assertThatThrownBy(() -> validator.validate(PROFILE, Map.of("techam", List.of("Platform"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("metadata.attribute.undeclared");
    }

    @Test
    void refusesAMissingRequiredAttribute() {
        declares(definition("team", AttributeDataType.STRING, List.of(), false, true));

        assertThatThrownBy(() -> validator.validate(PROFILE, Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("metadata.attribute.required");
        assertThatThrownBy(() -> validator.validate(PROFILE, Map.of("team", List.of("  "))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesAValueOutsideAnEnum() {
        declares(definition("region", AttributeDataType.ENUM, List.of("emea", "apac"), false, false));

        assertThatThrownBy(() -> validator.validate(PROFILE, Map.of("region", List.of("latam"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("metadata.attribute.invalid");
        assertThatCode(() -> validator.validate(PROFILE, Map.of("region", List.of("apac"))))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAValueThatDoesNotFitItsDeclaredType() {
        declares(definition("headcount", AttributeDataType.INTEGER, List.of(), false, false),
                definition("startedOn", AttributeDataType.DATE, List.of(), false, false));

        assertThatThrownBy(() -> validator.validate(PROFILE, Map.of("headcount", List.of("twelve"))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate(PROFILE, Map.of("startedOn", List.of("31/12/2026"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesSeveralValuesForASingleValuedAttribute() {
        declares(definition("team", AttributeDataType.STRING, List.of(), false, false));

        assertThatThrownBy(() -> validator.validate(PROFILE, Map.of("team", List.of("A", "B"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("metadata.attribute.singleValued");
    }

    /** Built-ins are app_user columns written through their own fields, never as profile attributes. */
    @Test
    void aBuiltInKeyIsNotAcceptedAsAnAttribute() {
        when(definitions.definitionsIn(any())).thenReturn(BaseAttributes.definitions());

        assertThatThrownBy(() -> validator.validate(PROFILE, Map.of("email", List.of("x@example.com"))))
                .isInstanceOf(BadRequestException.class);
    }
}
