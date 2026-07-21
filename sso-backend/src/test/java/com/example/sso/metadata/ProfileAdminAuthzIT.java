package com.example.sso.metadata;

import com.example.sso.metadata.internal.api.ProfileAdminController;
import com.example.sso.metadata.internal.api.ProfileMappingRequest;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.rbac.Permissions;
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
import org.springframework.security.core.context.SecurityContextHolder;

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
}
