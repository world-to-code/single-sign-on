package com.example.sso.metadata;

import com.example.sso.metadata.internal.api.ProfileAdminController;
import com.example.sso.metadata.internal.api.ProfileMappingRequest;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.rbac.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Method security on the profile admin API.
 *
 * <p>This matters more than the usual CRUD gate. A mapping decides which values an identity source may write
 * onto the tenant's attributes, and those attributes grant roles through mapping rules and select the auth and
 * session policy through policy bindings. So the write permission here reaches authorization, and the reach is
 * exactly why the read permission must not be enough.
 *
 * <p>Calls hit the controller bean directly so the method-security proxy applies.
 */
class ProfileAdminAuthzIT extends AbstractIntegrationTest {

    @Autowired ProfileAdminController controller;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void withoutAttributeDefinitionPermissionsEveryEndpointIsDenied() {
        actAs(); // authenticated, no attribute-definition authorities

        assertDenied(controller::list);
        assertDenied(() -> controller.attributes(UUID.randomUUID()));
        assertDenied(() -> controller.mappings(UUID.randomUUID()));
        assertDenied(() -> controller.map(UUID.randomUUID(), mappingRequest()));
        assertDenied(() -> controller.unmap(UUID.randomUUID(), UUID.randomUUID()));
    }

    /** Reading the schema is not permission to change where a directory's values land. */
    @Test
    void theReadPermissionDoesNotAllowChangingAMapping() {
        actAs(Permissions.ATTRIBUTE_DEFINITION_READ);

        assertThatCode(controller::list).doesNotThrowAnyException();
        assertThatCode(() -> controller.mappings(UUID.randomUUID())).doesNotThrowAnyException();

        assertDenied(() -> controller.map(UUID.randomUUID(), mappingRequest()));
        assertDenied(() -> controller.unmap(UUID.randomUUID(), UUID.randomUUID()));
    }

    /** And the write permission does not confer the read routes' absence of a gate — both are annotated. */
    @Test
    void theWritePermissionAloneDoesNotOpenTheReads() {
        actAs(Permissions.ATTRIBUTE_DEFINITION_WRITE);

        assertDenied(controller::list);
        assertDenied(() -> controller.mappings(UUID.randomUUID()));
    }

    private ProfileMappingRequest mappingRequest() {
        return new ProfileMappingRequest("department", UUID.randomUUID(), "team");
    }

    private void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }

    private void actAs(String... authorities) {
        List<SimpleGrantedAuthority> granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("profiler", null, granted));
    }
    /**
     * The list above is maintained by hand, and the CSV routes were added after it — so it claimed "every
     * endpoint" while covering five of eight. Assert the property structurally instead: a route with no
     * permission on it fails here the moment it is written, which is the only version of this test that keeps
     * being true.
     */
    @Test
    void everyRouteOnThisControllerCarriesAPermission() {
        List<String> ungated = Arrays.stream(ProfileAdminController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                // PreAuthorize, not RequirePermission: the CSV routes carry @CanImportUsers, which adds the
                // scope term the console's create has. Asking for one particular annotation would have made
                // this test fail the moment a route was gated MORE strictly.
                .filter(method -> !AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class))
                .map(Method::getName)
                .toList();

        assertThat(ungated).isEmpty();
    }

    /**
     * A CSV import becomes accounts, so it is gated as a write even though the preview writes nothing — the
     * schema-read permission the template download uses is deliberately not enough.
     */
    @Test
    void previewingAnImportNeedsTheCreatePermissionNotTheSchemaOne() {
        actAs(Permissions.ATTRIBUTE_DEFINITION_READ, Permissions.ATTRIBUTE_DEFINITION_WRITE);

        assertDenied(() -> controller.previewCsvImport(UUID.randomUUID(), new MockMultipartHttpServletRequest()));
    }

    @Test
    void theSchemaReadPermissionIsEnoughForTheTemplate() {
        actAs(Permissions.ATTRIBUTE_DEFINITION_READ);

        // Reaches the service (which then refuses the unknown profile) rather than being denied by authority.
        assertThatThrownBy(() -> controller.csvTemplate(UUID.randomUUID()))
                .isNotInstanceOf(AccessDeniedException.class);
    }
    /**
     * The permission is not the whole gate. A resource-subtree delegate holding user:create is refused by the
     * console's own create — canCreateUser() is false for anyone who is neither a super admin nor the bound
     * organization's administrator — and the import route must refuse them for the same reason. Without this
     * term a delegate could mint accounts across the entire tenant, five thousand at a time, through the one
     * route that skipped the ABAC half.
     */
    @Test
    void holdingTheCreatePermissionWithoutTheScopeIsNotEnoughToImport() {
        actAs(Permissions.USER_CREATE);

        assertDenied(() -> controller.previewCsvImport(UUID.randomUUID(), new MockMultipartHttpServletRequest()));
        assertDenied(() -> controller.importCsv(UUID.randomUUID(), new MockMultipartHttpServletRequest()));
    }
}
