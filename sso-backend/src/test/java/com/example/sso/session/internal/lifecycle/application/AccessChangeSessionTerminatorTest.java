package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.organization.OrganizationAccessRevokedEvent;
import com.example.sso.user.account.UserAccessChangedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * The AFTER_COMMIT access-change listeners translate each event into the right re-drivable termination request
 * and hand it to the resilient wrapper: a disable/lock/re-role carries the username directly, an org-membership
 * revoke carries the user id (resolved when driven). Both scope to the event's org so a same-named user in
 * another tenant is untouched.
 */
@ExtendWith(MockitoExtension.class)
class AccessChangeSessionTerminatorTest {

    @Mock
    private ResilientSessionTermination termination;
    @InjectMocks
    private AccessChangeSessionTerminator terminator;

    @Test
    void aUserAccessChangeTerminatesByUsernameInTheEventsOrg() {
        UUID org = UUID.randomUUID();

        terminator.onUserAccessChanged(new UserAccessChangedEvent("alice", org));

        verify(termination).run(SessionTerminationRequest.forUser("alice", org));
    }

    @Test
    void anOrgMembershipRevokeTerminatesByUserIdInThatOrgOnly() {
        UUID org = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        terminator.onOrganizationAccessRevoked(new OrganizationAccessRevokedEvent(org, userId));

        verify(termination).run(SessionTerminationRequest.forMember(userId, org));
    }
}
