package com.example.sso.branding.internal.api;

import com.example.sso.branding.Branding;
import com.example.sso.branding.ScreenCopy;
import com.example.sso.branding.AuthScreen;
import java.util.Map;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.internal.application.BrandingService;
import com.example.sso.organization.HostOrganizations;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the PUBLIC branding endpoint: the tenant is selected by the request host, and the read runs
 * inside that org's context. A resolvable host returns that org's branding; a host that names no servable
 * tenant returns the built-in default.
 *
 * <p>WHICH hosts resolve is not asked here — that rule belongs to {@link HostOrganizations} and is covered by
 * its own matrix (active, suspended, unknown, not a tenant address). This mocks it to pin the wiring: that the
 * request host is what selects the org, and that the read runs inside it.
 */
class BrandingControllerTest {

    private static final UUID ORG = UUID.randomUUID();

    private final BrandingService service = mock(BrandingService.class);
    private final HostOrganizations hostOrganizations = mock(HostOrganizations.class);
    private final OrgContext orgContext = mock(OrgContext.class);
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new BrandingController(service, hostOrganizations, orgContext)).build();
        // Execute the supplier callInOrg wraps, so the resolve actually runs.
        when(orgContext.callInOrg(any(), any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(1)).get());
    }

    @Test
    void aResolvableActiveHostReturnsThatTenantsBranding() throws Exception {
        when(hostOrganizations.activeOrgForHost(any())).thenReturn(Optional.of(ORG));
        when(service.resolve(ORG)).thenReturn(new Branding(
                new BrandingIdentity("https://cdn.acme.example/l.png", null, null, "Acme"),
                new BrandingTheme("#123abc", null, null, null, null, null), Map.of()));

        mvc.perform(get("/api/auth/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.productName").value("Acme"))
                .andExpect(jsonPath("$.theme.accentColor").value("#123abc"));

        verify(orgContext).callInOrg(eq(ORG), any()); // the read is bound to the HOST-resolved org, not another
    }

    @Test
    void anUnresolvableHostReturnsTheBuiltInDefault() throws Exception {
        when(hostOrganizations.activeOrgForHost(any())).thenReturn(Optional.empty());
        when(service.resolve(any())).thenReturn(Branding.platformDefault());

        mvc.perform(get("/api/auth/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.productName").value("Svalinn"))
                .andExpect(jsonPath("$.identity.logoUrl").doesNotExist())
                // The built-in style choices ship even when nobody configured branding, so a screen always
                // has a complete theme to render rather than half a stylesheet.
                .andExpect(jsonPath("$.theme.font").value("SANS"))
                .andExpect(jsonPath("$.theme.layout").value("CENTERED"));
    }

    /**
     * The public payload carries the record's COMPONENTS and nothing else.
     *
     * <p>This is a regression guard with a scar behind it: {@code ScreenCopy} had an {@code isEmpty()} helper,
     * which Jackson picked up as a bean property, so every screen shipped {@code "empty": false} to every
     * unauthenticated visitor and then could not be read back. No test asserted the exact shape, so nothing
     * caught it — asserting a field is PRESENT never says the payload has no others.
     */
    @Test
    void theScreenWordingCarriesNoFieldBeyondItsComponents() throws Exception {
        when(hostOrganizations.activeOrgForHost(any())).thenReturn(Optional.of(ORG));
        when(service.resolve(ORG)).thenReturn(new Branding(BrandingIdentity.none(), BrandingTheme.none(),
                Map.of(AuthScreen.LOGIN, new ScreenCopy("Sign in to Acme", null, null, null))));

        mvc.perform(get("/api/auth/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copy.LOGIN.headline").value("Sign in to Acme"))
                .andExpect(jsonPath("$.copy.LOGIN.length()").value(4))
                .andExpect(jsonPath("$.copy.LOGIN.empty").doesNotExist())
                // Every record, not just the one that was caught: an accessor added to Branding, identity or
                // theme ships the identical defect on the identical unauthenticated endpoint.
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.identity.length()").value(4))
                .andExpect(jsonPath("$.theme.length()").value(6));
    }
}
