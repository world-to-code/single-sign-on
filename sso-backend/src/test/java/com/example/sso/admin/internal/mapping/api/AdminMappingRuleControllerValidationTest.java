package com.example.sso.admin.internal.mapping.api;

import com.example.sso.admin.internal.mapping.application.AdminMappingRuleService;
import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.MappingRuleView;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.mapping.MappingTargetKind;
import java.util.List;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.account.UserService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer contract of the mapping-rule admin API that the direct-bean-call authz test ({@code
 * MappingRuleAuthzIT}) cannot reach: {@code @Valid} bean-validation runs during request-body argument
 * resolution, so a malformed body is rejected 400 BEFORE any work — a validation regression (e.g. dropping the
 * key {@code @Pattern}) would otherwise let a malformed predicate through. Also guards that the mutating
 * endpoints keep their {@code @RequireStepUp} sensitive-action gate (the gate's ENFORCEMENT is proven by
 * {@code StepUpInterceptorTest}; this pins that these endpoints stay declared sensitive). A standalone MVC setup
 * exercises validation in isolation from security.
 */
class AdminMappingRuleControllerValidationTest {

    private final AdminMappingRuleService service = mock(AdminMappingRuleService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminMappingRuleController(service, mock(UserService.class)))
                .build();
    }

    private void expectStatus(String requestBody, int expected) throws Exception {
        mvc.perform(post("/api/admin/mapping-rules").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().is(expected));
    }

    private String body(String attrKey, String attrValue, String thenKind, String targetId) {
        return """
                {"conditions":[{"attrKey":%s,"attrValue":%s}],"thenKind":%s,"targetId":%s}"""
                .formatted(quote(attrKey), quote(attrValue), quote(thenKind), quote(targetId));
    }

    private String quote(String value) {
        return value == null ? "null" : '"' + value + '"';
    }

    @Test
    void aValidBodyPassesValidationAndIsCreated() throws Exception {
        when(service.create(any())).thenReturn(new MappingRuleView(
                "id", List.of(new MappingCondition("dept", AttributeOperator.EQUALS, "eng")),
                MappingTargetKind.GROUP, UUID.randomUUID().toString(), "Engineering", 0));

        expectStatus(body("dept", "eng", "GROUP", UUID.randomUUID().toString()), 201);
    }

    @Test
    void anEmptyConditionListIsRejected() throws Exception {
        String target = UUID.randomUUID().toString();
        expectStatus("""
                {"conditions":[],"thenKind":"GROUP","targetId":"%s"}""".formatted(target), 400);
    }

    @Test
    void aBlankAttrKeyIsRejected() throws Exception {
        expectStatus(body("", "eng", "GROUP", UUID.randomUUID().toString()), 400);
    }

    @Test
    void anAttrKeyNotMatchingThePatternIsRejected() throws Exception {
        expectStatus(body("_leading", "eng", "GROUP", UUID.randomUUID().toString()), 400); // must start alnum
    }

    @Test
    void anOversizedAttrKeyIsRejected() throws Exception {
        expectStatus(body("k".repeat(65), "eng", "GROUP", UUID.randomUUID().toString()), 400); // > 64
    }

    @Test
    void aBlankAttrValueIsRejected() throws Exception {
        expectStatus(body("dept", "", "GROUP", UUID.randomUUID().toString()), 400);
    }

    @Test
    void aNullThenKindIsRejected() throws Exception {
        expectStatus(body("dept", "eng", null, UUID.randomUUID().toString()), 400);
    }

    @Test
    void aNullTargetIdIsRejected() throws Exception {
        expectStatus(body("dept", "eng", "GROUP", null), 400);
    }

    @Test
    void everyMutatingEndpointKeepsItsStepUpGate() throws Exception {
        assertThat(AdminMappingRuleController.class.getMethod("create", MappingRuleRequest.class)
                .isAnnotationPresent(RequireStepUp.class)).as("create is step-up gated").isTrue();
        assertThat(AdminMappingRuleController.class.getMethod("update", UUID.class, MappingRuleRequest.class)
                .isAnnotationPresent(RequireStepUp.class)).as("update is step-up gated").isTrue();
        assertThat(AdminMappingRuleController.class.getMethod("delete", UUID.class)
                .isAnnotationPresent(RequireStepUp.class)).as("delete is step-up gated").isTrue();
    }
}
