package com.example.sso.admin.internal.audit.api;

import com.example.sso.admin.internal.shared.application.AdminService;
import com.example.sso.audit.export.AuditExportSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP shape of the audit-export API, which the console depends on and a service test cannot see.
 *
 * <p>This exists because that gap shipped once already. The service learned that a blank credential means
 * "keep the stored one", its tests passed, and the REQUEST record still carried {@code @NotBlank} — so every
 * such save would have been refused at binding, before the rule that was supposed to decide it ever ran. The
 * console's "leave blank to keep it" would have been a lie, and switching the export off would have demanded
 * a secret the operator was never shown.
 *
 * <p>A standalone setup on purpose: the permission, step-up and audit gates are declarations proven elsewhere,
 * and what is asserted here is binding and routing, which is what was actually wrong.
 */
class AdminAuditControllerTest {

    private final AuditExportSettingsService exportSettings = mock(AuditExportSettingsService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new AdminAuditController(mock(AdminService.class), exportSettings)).build();
    }

    /** The contract the console's write-only credential field rests on. */
    @Test
    void aBlankCredentialBindsSoTheServiceCanKeepTheStoredOne() throws Exception {
        putExport("""
                {"endpointUrl":"https://collector.example.com/ingest","credential":"","enabled":true,
                 "includePii":false}""", 204);
    }

    @Test
    void anAbsentCredentialBindsTheSameWay() throws Exception {
        putExport("""
                {"endpointUrl":"https://collector.example.com/ingest","enabled":false,"includePii":false}""", 204);
    }

    /** The destination is not optional in the same way — there is nothing to fall back on. */
    @Test
    void aBlankEndpointIsStillRefused() throws Exception {
        putExport("""
                {"endpointUrl":"","credential":"bearer","enabled":true,"includePii":false}""", 400);
    }

    /** Stopping the export must be reachable without the credential — hence a route, not only a flag. */
    @Test
    void theCollectorCanBeRemoved() throws Exception {
        mvc.perform(delete("/api/admin/audit/export")).andExpect(status().isNoContent());

        verify(exportSettings).delete();
    }

    private void putExport(String body, int expected) throws Exception {
        mvc.perform(put("/api/admin/audit/export").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }
}
