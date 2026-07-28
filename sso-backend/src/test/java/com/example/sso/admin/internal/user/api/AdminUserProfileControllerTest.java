package com.example.sso.admin.internal.user.api;

import com.example.sso.admin.internal.user.application.ProfileSwitchPreview;
import com.example.sso.admin.internal.user.application.UserAdminService;
import com.example.sso.admin.internal.user.application.UserDetailAdminService;
import com.example.sso.admin.internal.user.application.UserProfileAttributeService;
import com.example.sso.admin.internal.user.application.UserProfileService;
import com.example.sso.admin.internal.user.application.UserProvisioningService;
import com.example.sso.admin.internal.user.application.UserRecoveryAdminService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.Audited;
import com.example.sso.metadata.AttributeService;
import com.example.sso.shared.security.RequireStepUp;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the profile endpoints on the user detail page, which the authz IT
 * ({@code UserProfileEndpointAuthzIT}) cannot reach: bean validation runs while the request body is resolved,
 * so a body naming no profile must be refused BEFORE a destructive move begins. Also pins that the move keeps
 * its step-up gate and its audit record — enforcement of the former belongs to {@code StepUpInterceptorTest},
 * this says the endpoint stays declared sensitive. A standalone MVC setup exercises the mapping in isolation
 * from security.
 */
class AdminUserProfileControllerTest {

    private final UserProfileService userProfiles = mock(UserProfileService.class);
    private final UserProfileAttributeService profileAttributes = mock(UserProfileAttributeService.class);
    private final AttributeService metadata = mock(AttributeService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(profileAttributes.columnsOf(any())).thenReturn(List.of());
        when(metadata.attributesOf(any(), any())).thenReturn(List.of());
        mvc = MockMvcBuilders.standaloneSetup(new AdminUserController(
                        mock(UserAdminService.class), mock(UserProvisioningService.class),
                        mock(UserDetailAdminService.class), mock(UserRecoveryAdminService.class),
                        profileAttributes, userProfiles, metadata))
                .build();
    }

    @Test
    void aMoveNamingNoProfileIsRefusedBeforeAnythingIsDeleted() throws Exception {
        // Without validation the null would reach the service as "no target", and the move is a deletion.
        expectStatus("""
                {"profileId":null}""", 400);

        verify(userProfiles, never()).switchTo(any(), any(), any());
    }

    @Test
    void aMoveNamingAMalformedProfileIsRefused() throws Exception {
        expectStatus("""
                {"profileId":"not-a-uuid"}""", 400);

        verify(userProfiles, never()).switchTo(any(), any(), any());
    }

    @Test
    void aMoveNamingAProfileIsPerformedAndAnswersWithTheNewColumns() throws Exception {
        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();

        mvc.perform(put("/api/admin/users/" + user + "/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileId\":\"" + profile + "\"}"))
                .andExpect(status().isOk());

        verify(userProfiles).switchTo(user, profile, null);
        // Answering with the target profile's columns is what lets the console render the move's outcome
        // rather than the schema the person just left.
        verify(profileAttributes).columnsOf(user);
    }

    @Test
    void aPreviewWithoutATargetProfileIsRefused() throws Exception {
        mvc.perform(get("/api/admin/users/" + UUID.randomUUID() + "/profile/preview"))
                .andExpect(status().isBadRequest());

        verify(userProfiles, never()).preview(any(), any());
    }

    @Test
    void aPreviewReportsWhatTheMoveWouldCostWithoutWritingAnything() throws Exception {
        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        when(userProfiles.preview(user, profile))
                .thenReturn(new ProfileSwitchPreview(List.of("team"), List.of(), false));

        mvc.perform(get("/api/admin/users/" + user + "/profile/preview").param("profileId", profile.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedKeys[0]").value("team"))
                .andExpect(jsonPath("$.externallyManaged").value(false))
                .andExpect(jsonPath("$.blocked").value(false));

        verify(userProfiles, never()).switchTo(any(), any(), any());
    }

    /**
     * The console disables its confirm button on {@code blocked}, so that field IS the disclosure before a
     * role-retracting deletion. Asserted on the wire, not on the record: nothing else notices if it stops
     * being serialized, and the client then reads {@code undefined} and re-opens the button.
     */
    @Test
    void aBlockedPreviewSaysSoOnTheWire() throws Exception {
        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        when(userProfiles.preview(user, profile))
                .thenReturn(new ProfileSwitchPreview(List.of("employeeId"), List.of("employeeId"), false));

        mvc.perform(get("/api/admin/users/" + user + "/profile/preview").param("profileId", profile.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.blockedKeys[0]").value("employeeId"));
    }

    /** The other blocking reason travels too — preview must name every reason the confirm would refuse. */
    @Test
    void anExternallyManagedUserIsReportedBlockedOnTheWire() throws Exception {
        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        when(userProfiles.preview(user, profile))
                .thenReturn(new ProfileSwitchPreview(List.of(), List.of(), true));

        mvc.perform(get("/api/admin/users/" + user + "/profile/preview").param("profileId", profile.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externallyManaged").value(true))
                .andExpect(jsonPath("$.blocked").value(true));
    }

    /**
     * The move keeps its step-up gate and its audit declaration, and the declaration names a SUBJECT.
     *
     * <p>Both halves matter and neither is decoration. Without the annotation a refusal — a 403 on an
     * administrator target, a 409 on a stale preview — leaves no row at all, because the service-layer trail is
     * AFTER_COMMIT and therefore records successes only. Without {@code subject}/{@code subjectParam} the row
     * is written at subject NONE, which the scoped-admin audit view resolves by parsing a UUID and so drops
     * entirely — the most destructive operation on the page, invisible to the delegate worth watching.
     */
    @Test
    void theMoveIsStepUpGatedAndAuditedAgainstTheUserItTargets() throws Exception {
        Method move = AdminUserController.class.getMethod("switchProfile", UUID.class, SwitchProfileRequest.class);

        assertThat(move.isAnnotationPresent(RequireStepUp.class)).as("a profile move is step-up gated").isTrue();
        Audited audited = move.getAnnotation(Audited.class);
        assertThat(audited).as("a refused move must still leave a trail").isNotNull();
        assertThat(audited.subject()).isEqualTo(AuditSubjectType.USER);
        assertThat(audited.subjectParam()).isEqualTo("id");
    }

    /** The sibling write had the same subject-less row, and the same delegate could not see it. */
    @Test
    void theColumnSaveIsAuditedAgainstTheUserItTargets() throws Exception {
        Audited audited = AdminUserController.class
                .getMethod("replaceProfileAttributes", UUID.class, UserProfileAttributesRequest.class)
                .getAnnotation(Audited.class);

        assertThat(audited).isNotNull();
        assertThat(audited.subject()).isEqualTo(AuditSubjectType.USER);
        assertThat(audited.subjectParam()).isEqualTo("id");
    }

    private void expectStatus(String requestBody, int expected) throws Exception {
        mvc.perform(put("/api/admin/users/" + UUID.randomUUID() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().is(expected));
    }

    /**
     * The confirmed cost travels with the move. Preview and confirm are two requests, and what the second one
     * deletes can differ from what the first one showed — a sync adds an attribute, a declaration is dropped —
     * so the server needs the list the administrator actually saw in order to notice.
     */
    @Test
    void theKeysTheAdministratorConfirmedTravelWithTheMove() throws Exception {
        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();

        mvc.perform(put("/api/admin/users/" + user + "/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileId\":\"" + profile + "\",\"confirmedKeys\":[\"team\"]}"))
                .andExpect(status().isOk());

        verify(userProfiles).switchTo(user, profile, List.of("team"));
    }
}
