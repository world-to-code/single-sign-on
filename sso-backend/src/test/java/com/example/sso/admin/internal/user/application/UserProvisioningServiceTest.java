package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.ActingAdminTier;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
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
    @Mock private ProfileService profiles;
    @Mock private AttributeService attributes;
    @Mock private OrganizationService organizations;
    @Mock private ActingAdminTier tier;
    @Mock private AdminAuditLogger auditLogger;

    private UserProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new UserProvisioningService(userService, validator, profiles, attributes, organizations,
                tier, auditLogger);
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

        service.create(NewUserCommand.fromConsole(newUser, Map.of("team", List.of("Platform")), null));

        verify(userService).assignProfile(userId, profile);
        // One call for the whole write, not one per value — see AttributeService.addAll.
        verify(attributes).addAll(EntityKind.USER, userId.toString(), Map.of("team", List.of("Platform")));
    }
    /**
     * The administrator picked a profile on the form, so the account is bound to THAT one — the organization's
     * default is not consulted at all. Otherwise the form asks for one profile's required columns and the
     * server files the answers under another.
     */
    @Test
    void aChosenProfileIsUsedInsteadOfTheOrganizationsDefault() {
        UUID chosen = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        when(profiles.requireAssignable(chosen))
                .thenReturn(new Profile(chosen, "Contractor", ProfileKind.TENANT, null, false, true));
        UserAccount created = user(userId);
        when(userService.createUser(eq(newUser), eq(ORG), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, Map.of(), chosen));

        verify(userService).assignProfile(userId, chosen);
        verify(validator, never()).defaultForCreation();
        verify(validator).validate(eq(chosen), any());
    }

    /**
     * The chosen id is client input, so it is resolved rather than trusted — and the refusal must land BEFORE
     * the account exists. Unchecked, another tenant's profile id bound a new account across the tenant
     * boundary: the declaration lookup is org-scoped, so the foreign profile declared nothing and the
     * required-column check passed over an empty set.
     */
    @Test
    void aProfileTheCallersOrganizationDoesNotOwnRefusesTheCreation() {
        UUID foreign = UUID.randomUUID();
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        when(profiles.requireAssignable(foreign)).thenThrow(NotFoundException.of("metadata.profile.notFound"));

        assertThatThrownBy(() -> service.create(NewUserCommand.fromConsole(newUser, Map.of(), foreign)))
                .isInstanceOf(NotFoundException.class);

        verify(userService, never()).createUser(any(), any(), any());
    }

    /** Same guard, the other refusal: a SOURCE profile of the caller's own organization cannot govern a user. */
    @Test
    void aSourceProfileRefusesTheCreation() {
        UUID source = UUID.randomUUID();
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        when(profiles.requireAssignable(source))
                .thenThrow(BadRequestException.of("metadata.profile.notAssignable"));

        assertThatThrownBy(() -> service.create(NewUserCommand.fromConsole(newUser, Map.of(), source)))
                .isInstanceOf(BadRequestException.class);

        verify(userService, never()).createUser(any(), any(), any());
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

        service.create(NewUserCommand.fromConsole(newUser, Map.of("team", List.of("  ")), null));

        // Handed over as given; addAll is where a blank is skipped, and its own test says so.
        verify(attributes).addAll(EntityKind.USER, userId.toString(), Map.of("team", List.of("  ")));
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

        service.create(NewUserCommand.fromConsole(newUser, Map.of(), null));

        verify(organizations).addMember(org, userId);
    }
    @Test
    void createUserAsAnUnDrilledPlatformAdminAddsNoMembership() {
        when(tier.actingOrg()).thenReturn(null);
        // A global user (no home org) has no org to join; the platform-admin path must not touch memberships.
        NewUser newUser = new NewUser("root2", "root2@example.com", "Root", "pw", Set.of(Roles.USER));
        UserAccount created = user(UUID.randomUUID());
        when(userService.createUser(eq(newUser), any(), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, Map.of(), null));

        verify(organizations, never()).addMember(any(), any());
    }
    @Test
    void createUserSurfacesDomainIllegalArgumentAsConflict() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of());
        when(userService.createUser(eq(newUser), any(), any()))
                .thenThrow(new IllegalArgumentException("username taken"));

        assertThatThrownBy(() -> service.create(NewUserCommand.fromConsole(newUser, Map.of(), null)))
                .isInstanceOf(ConflictException.class);
    }
    @Test
    void createUserAuditsTheCreation() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        UserAccount created = user(UUID.randomUUID());
        when(userService.createUser(eq(newUser), any(), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, Map.of(), null));

        verify(auditLogger).log(eq(AuditType.USER_CREATED), eq(AuditSubjectType.USER), any(), any());
    }
    @Test
    void createUserWithATemporaryPasswordRequiresAResetOnFirstLogin() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "temp-pass", Set.of(Roles.USER));
        UUID newId = UUID.randomUUID();
        UserAccount created = user(newId);
        when(userService.createUser(eq(newUser), any(), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, Map.of(), null));

        verify(userService).requirePasswordReset(newId);
    }
    @Test
    void createUserWithoutAPasswordDoesNotRequireAReset() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", null, Set.of(Roles.USER));
        UserAccount created = user(UUID.randomUUID());
        when(userService.createUser(eq(newUser), any(), any())).thenReturn(created);

        service.create(NewUserCommand.fromConsole(newUser, Map.of(), null));

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

        assertThatThrownBy(() -> service.create(NewUserCommand.fromConsole(newUser, Map.of("team", List.of("")), null)))
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
