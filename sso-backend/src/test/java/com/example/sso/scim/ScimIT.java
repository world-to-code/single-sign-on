package com.example.sso.scim;

import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the SCIM 2.0 server: bearer-token auth, and that provisioning a user via SCIM
 * creates a real domain identity (full create / read / list / delete lifecycle).
 */
@AutoConfigureMockMvc
@ActiveProfiles("local") // activates ScimTokenSeeder so the well-known dev token exists for this test
class ScimIT extends AbstractIntegrationTest {

    private static final String BEARER = "Bearer dev-scim-token";

    @Autowired
    MockMvc mvc;

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mvc.perform(get("/scim/v2/Users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void provisionsAndDeprovisionsUser() throws Exception {
        String createBody = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"scim-it-user","name":{"formatted":"SCIM IT"},
                 "emails":[{"value":"scim-it@example.com","primary":true}],
                 "active":true,"externalId":"ext-it-1"}
                """;
        String created = mvc.perform(post("/scim/v2/Users")
                        .header("Authorization", BEARER)
                        .contentType("application/scim+json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(created).contains("\"userName\":\"scim-it-user\"").contains("ext-it-1");
        String id = jsonValue(created, "id");
        assertThat(id).isNotBlank();

        mvc.perform(get("/scim/v2/Users/" + id).header("Authorization", BEARER))
                .andExpect(status().isOk());

        mvc.perform(get("/scim/v2/Users").header("Authorization", BEARER))
                .andExpect(status().isOk());

        mvc.perform(delete("/scim/v2/Users/" + id).header("Authorization", BEARER))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scim/v2/Users/" + id).header("Authorization", BEARER))
                .andExpect(status().isNotFound());
    }

    private static String jsonValue(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
