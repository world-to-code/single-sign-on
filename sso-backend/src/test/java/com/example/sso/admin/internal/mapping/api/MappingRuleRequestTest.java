package com.example.sso.admin.internal.mapping.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping-rule request's bean-validation: the attribute key is a bounded identifier and the value bounded
 * free text (mirroring the metadata store), and a group is required. Guards against the constraints being
 * loosened — a malformed predicate must never reach the service.
 */
class MappingRuleRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aWellFormedRequestIsAccepted() {
        assertThat(validator.validate(new MappingRuleRequest("department", "engineering", UUID.randomUUID()))).isEmpty();
    }

    @Test
    void aBlankOrIllegalOrOversizedKeyIsRejected() {
        assertThat(validator.validate(new MappingRuleRequest(" ", "eng", UUID.randomUUID()))).isNotEmpty();       // blank
        assertThat(validator.validate(new MappingRuleRequest("has space", "eng", UUID.randomUUID()))).isNotEmpty(); // pattern
        assertThat(validator.validate(new MappingRuleRequest("a".repeat(65), "eng", UUID.randomUUID()))).isNotEmpty(); // > 64
    }

    @Test
    void aBlankOrOversizedValueIsRejected() {
        assertThat(validator.validate(new MappingRuleRequest("dept", " ", UUID.randomUUID()))).isNotEmpty();
        assertThat(validator.validate(new MappingRuleRequest("dept", "v".repeat(256), UUID.randomUUID()))).isNotEmpty();
    }

    @Test
    void aMissingGroupIsRejected() {
        assertThat(validator.validate(new MappingRuleRequest("dept", "eng", null))).isNotEmpty();
    }

    @Test
    void toSpecCarriesThePredicateAndGroup() {
        UUID group = UUID.randomUUID();
        assertThat(new MappingRuleRequest("dept", "eng", group).toSpec())
                .satisfies(s -> {
                    assertThat(s.attrKey()).isEqualTo("dept");
                    assertThat(s.attrValue()).isEqualTo("eng");
                    assertThat(s.groupId()).isEqualTo(group);
                });
    }
}
