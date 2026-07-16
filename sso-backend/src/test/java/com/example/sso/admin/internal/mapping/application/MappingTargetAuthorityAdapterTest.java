package com.example.sso.admin.internal.mapping.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.user.account.UserService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The admin adapter for the mapping module's author re-validation port: it must feed the author's CURRENT
 * effective authority set (by id, off the request thread) into the parameterized grant-authority policy — the
 * same decision the manual gate makes — and fail closed when the author resolves to no authority.
 */
class MappingTargetAuthorityAdapterTest {

    private final AdminAccessPolicy accessPolicy = mock(AdminAccessPolicy.class);
    private final UserService userService = mock(UserService.class);
    private final MappingTargetAuthorityAdapter adapter = new MappingTargetAuthorityAdapter(accessPolicy, userService);

    @Test
    void feedsTheAuthorsEffectiveAuthoritiesIntoTheParameterizedPolicy() {
        UUID author = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        Set<String> authorities = Set.of("user:read");
        when(userService.effectiveAuthorities(author)).thenReturn(authorities);
        when(accessPolicy.mayAssignTarget(author, authorities, MappingTargetKind.ROLE, role)).thenReturn(true);

        assertThat(adapter.authorMayAssign(author, MappingTargetKind.ROLE, role)).isTrue();
    }

    @Test
    void failsClosedWhenTheAuthorResolvesToNoAuthority() {
        UUID author = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        when(userService.effectiveAuthorities(author)).thenReturn(Set.of()); // unknown/deleted author
        when(accessPolicy.mayAssignTarget(author, Set.of(), MappingTargetKind.ROLE, role)).thenReturn(false);

        assertThat(adapter.authorMayAssign(author, MappingTargetKind.ROLE, role)).isFalse();
    }
}
