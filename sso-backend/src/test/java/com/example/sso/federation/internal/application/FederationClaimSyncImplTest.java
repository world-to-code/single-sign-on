package com.example.sso.federation.internal.application;

import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The login-time claim write: a mapped claim reaches the user through {@code applyFromDirectory}, an absent or
 * over-length one is skipped, a missing source/profile is a no-op, and the directory-ownership refusal is
 * swallowed (never failing the sign-in). The write runs inside the tenant's org context, which the caller binds.
 */
@ExtendWith(MockitoExtension.class)
class FederationClaimSyncImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OIDC = UUID.randomUUID();
    private static final String USER = UUID.randomUUID().toString();

    @Mock ProfileService profiles;
    @Mock ProfileMappingService mappings;
    @Mock AttributeService attributes;
    @Mock OrgContext orgContext;

    private FederationClaimSyncImpl sync() {
        return new FederationClaimSyncImpl(profiles, mappings, attributes, orgContext);
    }

    private void inlineOrg() {
        doAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return null;
        }).when(orgContext).runInOrg(eq(ORG), any());
    }

    private void profilesResolve() {
        lenient().when(profiles.tenantProfile())
                .thenReturn(Optional.of(new Profile(TENANT, "acme", ProfileKind.TENANT, null, true, true)));
        lenient().when(profiles.list()).thenReturn(List.of(
                new Profile(TENANT, "acme", ProfileKind.TENANT, null, true, true),
                new Profile(OIDC, "OIDC", ProfileKind.OIDC, null, false, false)));
    }

    private ProfileMapping mapping(String key) {
        return new ProfileMapping(UUID.randomUUID(), OIDC, key, TENANT, key);
    }

    @Test
    void writesAMappedClaimThroughApplyFromDirectory() {
        inlineOrg();
        profilesResolve();
        when(mappings.mappingsFrom(OIDC)).thenReturn(List.of(mapping("given_name")));

        sync().applyClaims(ORG, USER, Map.of("given_name", "Ada"));

        verify(attributes).applyFromDirectory(EntityKind.USER, USER, "given_name", List.of("Ada"));
    }

    @Test
    void skipsAClaimTheTokenDidNotCarry() {
        inlineOrg();
        profilesResolve();
        when(mappings.mappingsFrom(OIDC)).thenReturn(List.of(mapping("family_name")));

        sync().applyClaims(ORG, USER, Map.of("given_name", "Ada")); // family_name absent from the token

        verify(attributes, never()).applyFromDirectory(any(), any(), any(), any());
    }

    @Test
    void skipsAnOverLengthValueRatherThanFailingTheInsert() {
        inlineOrg();
        profilesResolve();
        when(mappings.mappingsFrom(OIDC)).thenReturn(List.of(mapping("picture")));

        sync().applyClaims(ORG, USER, Map.of("picture", "x".repeat(256))); // attr_value is varchar(255)

        verify(attributes, never()).applyFromDirectory(any(), any(), any(), any());
    }

    @Test
    void isANoOpWhenNoOidcSourceProfileExists() {
        inlineOrg();
        when(profiles.tenantProfile())
                .thenReturn(Optional.of(new Profile(TENANT, "acme", ProfileKind.TENANT, null, true, true)));
        when(profiles.list()).thenReturn(List.of(new Profile(TENANT, "acme", ProfileKind.TENANT, null, true, true)));

        sync().applyClaims(ORG, USER, Map.of("given_name", "Ada"));

        verify(attributes, never()).applyFromDirectory(any(), any(), any(), any());
    }

    @Test
    void swallowsTheOwnershipRefusalSoTheLoginStillSucceeds() {
        inlineOrg();
        profilesResolve();
        when(mappings.mappingsFrom(OIDC)).thenReturn(List.of(mapping("given_name")));
        doThrow(ConflictException.of("attribute.locallyOwned", "given_name"))
                .when(attributes).applyFromDirectory(any(), any(), any(), any());

        assertThatCode(() -> sync().applyClaims(ORG, USER, Map.of("given_name", "Ada")))
                .doesNotThrowAnyException(); // a refused claim must not fail the sign-in
    }

    @Test
    void aTransientFailureNeverDeniesTheSignIn() {
        // Not a ConflictException — a DB blip, or a uniqueness collision between two concurrent logins. It must
        // still not propagate out and fail the login (the write is a best-effort side effect of signing in).
        inlineOrg();
        profilesResolve();
        when(mappings.mappingsFrom(OIDC)).thenReturn(List.of(mapping("given_name")));
        doThrow(new IllegalStateException("db blip"))
                .when(attributes).applyFromDirectory(any(), any(), any(), any());

        assertThatCode(() -> sync().applyClaims(ORG, USER, Map.of("given_name", "Ada")))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotBindAContextForAnEmptyClaimSet() {
        sync().applyClaims(ORG, USER, Map.of());

        verify(orgContext, never()).runInOrg(any(), any());
    }
}
