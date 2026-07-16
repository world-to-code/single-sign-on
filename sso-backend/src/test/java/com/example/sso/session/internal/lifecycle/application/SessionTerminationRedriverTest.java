package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.session.lifecycle.UserSessions;
import com.example.sso.user.account.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The re-driver reconstructs a termination from persisted data alone. A username request drives it directly; a
 * member request resolves the username from the user id (via the RLS-bypassing lookup) first; a member whose
 * user no longer resolves (deleted before the re-drive) is a safe no-op — 0 sessions, no termination call —
 * never an NPE that would strand the durable sweep.
 */
@ExtendWith(MockitoExtension.class)
class SessionTerminationRedriverTest {

    @Mock
    private UserSessions sessions;
    @Mock
    private UserService users;

    private SessionTerminationRedriver redriver() {
        return new SessionTerminationRedriver(sessions, users);
    }

    @Test
    void aUsernameRequestTerminatesThatUserWithoutResolvingAnyId() {
        UUID org = UUID.randomUUID();
        when(sessions.terminateForUser("alice", org)).thenReturn(2);

        int count = redriver().redrive(SessionTerminationRequest.forUser("alice", org));

        assertThat(count).isEqualTo(2);
        verify(users, never()).usernameOf(any());
    }

    @Test
    void aMemberRequestResolvesTheUsernameThenTerminatesInThatOrg() {
        UUID org = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(users.usernameOf(userId)).thenReturn(Optional.of("bob"));
        when(sessions.terminateForUser("bob", org)).thenReturn(1);

        int count = redriver().redrive(SessionTerminationRequest.forMember(userId, org));

        assertThat(count).isEqualTo(1);
        verify(sessions).terminateForUser("bob", org);
    }

    @Test
    void aMemberRequestForAnUnresolvableUserIsANoOp() {
        UUID userId = UUID.randomUUID();
        when(users.usernameOf(userId)).thenReturn(Optional.empty());

        int count = redriver().redrive(SessionTerminationRequest.forMember(userId, UUID.randomUUID()));

        assertThat(count).isZero();
        verify(sessions, never()).terminateForUser(anyString(), any());
    }
}
