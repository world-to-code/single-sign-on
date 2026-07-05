package com.example.sso.auth;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant-first entry point: a fresh visitor is asked for the organization first; identify is scoped to the
 * resolved org and gates on membership — a member proceeds, a non-member is rejected the same way as an
 * unknown account (no cross-tenant account enumeration), and identify without a resolved org is refused.
 */
@AutoConfigureMockMvc
class TenantLoginIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    OrganizationService organizations;
    @Autowired
    UserService users;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void tenantFirstFlowResolvesOrgThenGatesIdentifyOnMembership() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String slug = "acme-" + s;
        UUID orgId = organizations.create(new NewOrganization(slug, "Acme")).id();
        UserAccount member = newUser("member-" + s);
        UserAccount outsider = newUser("outsider-" + s);
        organizations.addMember(orgId, member.getId());
        cleanups.add(() -> organizations.delete(orgId));

        // A fresh visitor must pick an organization first.
        mvc.perform(get("/api/auth/session")).andExpect(jsonPath("$.next").value("ORGANIZATION"));

        // Resolving the org advances to IDENTIFY and echoes the active org.
        Cookie session = sessionCookie(mvc.perform(post("/api/auth/organization").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("IDENTIFY"))
                .andExpect(jsonPath("$.org").value(slug))
                .andReturn(), null);

        // A member of the org proceeds past identify.
        mvc.perform(post("/api/auth/identify").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member-" + s + "@example.com\"}"))
                .andExpect(status().isOk());

        // A non-member is rejected with the same 404 as an unknown account (no enumeration).
        Cookie session2 = sessionCookie(mvc.perform(post("/api/auth/organization").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"" + slug + "\"}"))
                .andReturn(), null);
        mvc.perform(post("/api/auth/identify").cookie(session2).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"outsider-" + s + "@example.com\"}"))
                .andExpect(status().isNotFound());

        // Identify without a resolved org is refused.
        mvc.perform(post("/api/auth/identify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member-" + s + "@example.com\"}"))
                .andExpect(status().isBadRequest());

        // An unknown org slug is rejected.
        mvc.perform(post("/api/auth/organization").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"no-such-org-" + s + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theOrgCannotBeReselectedAfterIdentify() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String slug = "pin-" + s;
        UUID orgId = organizations.create(new NewOrganization(slug, "Pin")).id();
        UserAccount member = newUser("pin-" + s);
        organizations.addMember(orgId, member.getId());
        cleanups.add(() -> organizations.delete(orgId));

        Cookie session = sessionCookie(mvc.perform(post("/api/auth/organization").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"" + slug + "\"}"))
                .andReturn(), null);
        session = sessionCookie(mvc.perform(post("/api/auth/identify").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pin-" + s + "@example.com\"}"))
                .andExpect(status().isOk())
                .andReturn(), session);

        // Once identified (membership verified against this org), re-selecting an org is refused — a member
        // of this org cannot switch the session to another org that login completion would then bind.
        mvc.perform(post("/api/auth/organization").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private UserAccount newUser(String username) {
        UserAccount user = users.createUser(new NewUser(username, username + "@example.com", username,
                "pw-tenant-1!", Set.of("ROLE_USER")));
        users.markEmailVerified(user.getId());
        cleanups.add(() -> users.delete(user.getId()));
        return user;
    }
}
