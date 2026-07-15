package com.example.sso.admin.internal.mapping.api;

import com.example.sso.mapping.MappingTargetKind;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping-rule request's bean-validation: the attribute key is a bounded identifier and the value bounded
 * free text (mirroring the metadata store), and a kind + target are required. Guards against the constraints
 * being loosened — a malformed predicate must never reach the service.
 */
class MappingRuleRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private MappingRuleRequest request(String key, String value, MappingTargetKind kind, UUID target) {
        return new MappingRuleRequest(key, value, kind, target);
    }

    @Test
    void aWellFormedRequestIsAccepted() {
        assertThat(validator.validate(request("department", "engineering", MappingTargetKind.ROLE, UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    void aBlankOrIllegalOrOversizedKeyIsRejected() {
        UUID g = UUID.randomUUID();
        assertThat(validator.validate(request(" ", "eng", MappingTargetKind.GROUP, g))).isNotEmpty();          // blank
        assertThat(validator.validate(request("has space", "eng", MappingTargetKind.GROUP, g))).isNotEmpty();  // pattern
        assertThat(validator.validate(request("a".repeat(65), "eng", MappingTargetKind.GROUP, g))).isNotEmpty(); // > 64
    }

    @Test
    void aBlankOrOversizedValueIsRejected() {
        UUID g = UUID.randomUUID();
        assertThat(validator.validate(request("dept", " ", MappingTargetKind.GROUP, g))).isNotEmpty();
        assertThat(validator.validate(request("dept", "v".repeat(256), MappingTargetKind.GROUP, g))).isNotEmpty();
    }

    @Test
    void aMissingKindOrTargetIsRejected() {
        assertThat(validator.validate(request("dept", "eng", null, UUID.randomUUID()))).isNotEmpty();
        assertThat(validator.validate(request("dept", "eng", MappingTargetKind.GROUP, null))).isNotEmpty();
    }

    @Test
    void toSpecCarriesThePredicateKindAndTarget() {
        UUID target = UUID.randomUUID();
        assertThat(request("dept", "eng", MappingTargetKind.ROLE, target).toSpec())
                .satisfies(s -> {
                    assertThat(s.attrKey()).isEqualTo("dept");
                    assertThat(s.attrValue()).isEqualTo("eng");
                    assertThat(s.thenKind()).isEqualTo(MappingTargetKind.ROLE);
                    assertThat(s.targetId()).isEqualTo(target);
                });
    }
}
