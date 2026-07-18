package com.example.sso.branding.internal.api;

import com.example.sso.branding.Branding;
import com.example.sso.branding.internal.application.BrandingService;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.SubdomainTenantResolver;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the PUBLIC branding endpoint: the tenant is selected by the request host, and the read runs
 * inside that org's context. A resolvable (ACTIVE) host returns that org's branding; an unresolvable host
 * returns the built-in default. The host→org primitives are mocked to pin the controller wiring.
 */
class BrandingControllerTest {

    private static final UUID ORG = UUID.randomUUID();

    private final BrandingService service = mock(BrandingService.class);
    private final SubdomainTenantResolver tenantResolver = mock(SubdomainTenantResolver.class);
    private final OrganizationService organizations = mock(OrganizationService.class);
    private final OrgContext orgContext = mock(OrgContext.class);
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new BrandingController(service, tenantResolver, organizations, orgContext)).build();
        // Execute the supplier callInOrg wraps, so the resolve actually runs.
        when(orgContext.callInOrg(any(), any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(1)).get());
    }

    @Test
    void aResolvableActiveHostReturnsThatTenantsBranding() throws Exception {
        OrganizationRef ref = mock(OrganizationRef.class);
        when(ref.getStatus()).thenReturn(OrganizationStatus.ACTIVE);
        when(ref.getId()).thenReturn(ORG);
        when(tenantResolver.tenantSlug(any())).thenReturn(Optional.of("acme"));
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(ref));
        when(service.resolve(ORG)).thenReturn(new Branding("https://cdn.acme.example/l.png", "#123abc", "Acme"));

        mvc.perform(get("/api/auth/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Acme"))
                .andExpect(jsonPath("$.accentColor").value("#123abc"));

        verify(orgContext).callInOrg(eq(ORG), any()); // the read is bound to the HOST-resolved org, not another
    }

    @Test
    void anUnresolvableHostReturnsTheBuiltInDefault() throws Exception {
        when(tenantResolver.tenantSlug(any())).thenReturn(Optional.empty());
        when(service.resolve(any())).thenReturn(Branding.platformDefault());

        mvc.perform(get("/api/auth/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Mini SSO"))
                .andExpect(jsonPath("$.logoUrl").doesNotExist());
    }
}
