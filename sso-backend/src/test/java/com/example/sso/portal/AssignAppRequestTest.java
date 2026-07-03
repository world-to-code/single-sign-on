package com.example.sso.portal;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subjectType pattern must accept every subject kind the domain supports — USER, ROLE, and GROUP
 * (app-to-group assignment). Regression guard for the validation pattern drifting from
 * {@code AppAssignment.SubjectType}.
 */
class AssignAppRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private AssignAppRequest request(String subjectType) {
        return new AssignAppRequest("OIDC", "app-1", subjectType, UUID.randomUUID().toString(), null);
    }

    @Test
    void userRoleAndGroupSubjectTypesAreAccepted() {
        assertThat(validator.validate(request("USER"))).isEmpty();
        assertThat(validator.validate(request("ROLE"))).isEmpty();
        assertThat(validator.validate(request("GROUP"))).isEmpty();
    }

    @Test
    void anUnknownSubjectTypeIsRejected() {
        assertThat(validator.validate(request("TEAM"))).isNotEmpty();
    }
}
