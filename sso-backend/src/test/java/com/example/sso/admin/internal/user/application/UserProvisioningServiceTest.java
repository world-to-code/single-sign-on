package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.ActingAdminTier;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.Roles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Assembling one account.
 *
 * <p>Split out of UserAdminServiceTest with the code it covers: creation was the only thing on that class
 * needing the profile validator, the attribute store and the organization service, and it had grown three
 * overloads whose last two parameters were a nullable UUID beside an enum — a swap the compiler could not see,
 * on the call that decides which accounts exist.
 */
@ExtendWith(MockitoExtension.class)
class UserProvisioningServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock private UserService userService;
    @Mock private ProfileAttributeValidator validator;
    @Mock private AttributeService attributes;
    @Mock private OrganizationService organizations;
    @Mock private ActingAdminTier tier;
    @Mock private AdminAuditLogger auditLogger;

    private UserProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new UserProvisioningService(userService, validator, attributes, organizations, tier,
                auditLogger);
        lenient().when(tier.actingOrg()).thenReturn(ORG);
    }

    @Test
    void aCreatedUserIsBoundToTheCreationProfileAndCarriesItsAttributes() {
        UUID org = ORG;
        UUID profile = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        when(validator.defaultForCreation()).thenReturn(profile);
        UserAccount created = user(userId); // the helper stubs, so it cannot run inside when(...)
        when(userService.createUser(eq(newUser), eq(org), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, Map.of("team", List.of("Platform"))));

        verify(userService).assignProfile(userId, profile);
        verify(attributes).add(EntityKind.USER, userId.toString(), "team", "Platform");
    }
    /** A blank value is "not supplied", not an empty attribute nobody can search for. */
    @Test
    void blankAttributeValuesAreNotStored() {
        UUID org = ORG;
        UUID profile = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        when(validator.defaultForCreation()).thenReturn(profile);
        UserAccount created = user(userId);
        when(userService.createUser(eq(newUser), eq(org), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, Map.of("team", List.of("  "))));

        verify(attributes, never()).add(any(), any(), any(), any());
    }
    @Test
    void createUserInATenantRecordsTheOrgMembership() {
        // A tenant admin's new user must be an explicit org member (not only carry a home org_id), so the
        // membership-table checks (e.g. delegating resource admin) recognise it.
        UUID org = ORG;
        UUID userId = UUID.randomUUID();
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        UserAccount created = user(userId);
        when(userService.createUser(eq(newUser), eq(org), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, java.util.Map.of()));

        verify(organizations).addMember(org, userId);
    }
    @Test
    void createUserAsAnUnDrilledPlatformAdminAddsNoMembership() {
        when(tier.actingOrg()).thenReturn(null);
        // A global user (no home org) has no org to join; the platform-admin path must not touch memberships.
        NewUser newUser = new NewUser("root2", "root2@example.com", "Root", "pw", Set.of(Roles.USER));
        UserAccount created = user(UUID.randomUUID());
        when(userService.createUser(eq(newUser), any(), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, java.util.Map.of()));

        verify(organizations, never()).addMember(any(), any());
    }
    @Test
    void createUserSurfacesDomainIllegalArgumentAsConflict() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of());
        when(userService.createUser(eq(newUser), any(), any()))
                .thenThrow(new IllegalArgumentException("username taken"));

        assertThatThrownBy(() -> service.create(NewUserCommand.fromConsole(newUser, Map.of())))
                .isInstanceOf(ConflictException.class);
    }
    @Test
    void createUserAuditsTheCreation() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        UserAccount created = user(UUID.randomUUID());
        when(userService.createUser(eq(newUser), any(), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, java.util.Map.of()));

        verify(auditLogger).log(eq(AuditType.USER_CREATED), eq(AuditSubjectType.USER), any(), any());
    }
    @Test
    void createUserWithATemporaryPasswordRequiresAResetOnFirstLogin() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "temp-pass", Set.of(Roles.USER));
        UUID newId = UUID.randomUUID();
        UserAccount created = user(newId);
        when(userService.createUser(eq(newUser), any(), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, java.util.Map.of()));

        verify(userService).requirePasswordReset(newId);
    }
    @Test
    void createUserWithoutAPasswordDoesNotRequireAReset() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", null, Set.of(Roles.USER));
        UserAccount created = user(UUID.randomUUID());
        when(userService.createUser(eq(newUser), any(), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, java.util.Map.of()));

        verify(userService, never()).requirePasswordReset(any());
    }
    /**
     * The attribute branch of createUser. Every other test here leaves defaultForCreation() returning null,
     * which skips validation, profile assignment and the attribute writes entirely — so without these the
     * whole feature could be deleted and the suite would stay green.
     */
    @Test
    void validationRunsBeforeTheAccountIsWritten() {
        UUID profile = UUID.randomUUID();
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        when(validator.defaultForCreation()).thenReturn(profile);
        doThrow(BadRequestException.of("metadata.attribute.required", "Team"))
                .when(validator).validate(eq(profile), any());

        assertThatThrownBy(() -> service.create(NewUserCommand.fromConsole(newUser, Map.of("team", List.of("")))))
                .isInstanceOf(BadRequestException.class);

        // The point of validating first: a rejected attribute must not leave a half-made account behind.
        verify(userService, never()).createUser(any(), any(), any());
    }
    /** Stubs inside, so it cannot be called from within a when(...) argument list. */
    private UserAccount user(UUID id) {
        UserAccount account = mock(UserAccount.class);
        lenient().when(account.getId()).thenReturn(id);
        lenient().when(account.getRoles()).thenReturn(Set.of());
        lenient().when(account.getDirectPermissionNames()).thenReturn(Set.of());
        return account;
    }
}
