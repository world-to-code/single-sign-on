package com.example.sso.resource;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.resource.catalog.ResourceDeletedEvent;
import com.example.sso.resource.catalog.ResourceMembershipService;
import com.example.sso.resource.internal.catalog.application.ResourceAdminService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceMemberRow;
import com.example.sso.resource.internal.domain.ResourceMemberRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The programmatic resource-membership port (consumed by auto-mapping): it adds a USER to a resource WITHOUT any
 * current-actor authorization, but keeps the integrity invariants — the type must allow USER, the user must
 * exist, and the user must live in the resource's org. Both writes are idempotent, and deleting a resource
 * publishes {@link ResourceDeletedEvent}.
 */
@RecordApplicationEvents
class ResourceMembershipServiceIT extends AbstractIntegrationTest {

    @Autowired ResourceMembershipService membership;
    @Autowired ResourceAdminService adminService;
    @Autowired ResourceRepository resources;
    @Autowired ResourceTypeRepository types;
    @Autowired ResourceTypeAllowedMemberRepository allowedMembers;
    @Autowired ResourceMemberRowRepository memberRows;
    @Autowired UserService users;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;
    @Autowired ApplicationEvents events;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdOrgs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        orgContext.runAsPlatform(() -> {
            memberRows.deleteAll();
            resources.deleteAll();
            types.deleteAll();
            createdUsers.forEach(users::delete);
            createdOrgs.forEach(id -> ownerJdbc().update("delete from organization where id = ?", id));
        });
        createdUsers.clear();
        createdOrgs.clear();
    }

    @Test
    void addsAUserWithNoActorAuthorization() {
        // No SecurityContext at all — the admin path's requireManage would 403 here; the port does not.
        UUID resource = globalResource("res", MemberType.USER);
        UUID user = globalUser();

        membership.addUser(resource, user);

        assertThat(memberIds(resource)).containsExactly(user.toString());
    }

    @Test
    void rejectsAUserFromADifferentOrg() {
        UUID resource = globalResource("res", MemberType.USER); // org = null (global)
        UUID orgUser = orgUser(newOrg());                        // belongs to a tenant

        assertThatThrownBy(() -> membership.addUser(resource, orgUser))
                .isInstanceOf(BadRequestException.class); // same-org invariant preserved
        assertThat(memberIds(resource)).isEmpty();
    }

    @Test
    void rejectsAUserWhenTheTypeDoesNotAllowUsers() {
        UUID resource = globalResource("res", MemberType.GROUP); // USER not among the allowed member types
        UUID user = globalUser();

        assertThatThrownBy(() -> membership.addUser(resource, user)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsANonexistentUser() {
        UUID resource = globalResource("res", MemberType.USER);

        assertThatThrownBy(() -> membership.addUser(resource, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void addUserIsIdempotent() {
        UUID resource = globalResource("res", MemberType.USER);
        UUID user = globalUser();

        membership.addUser(resource, user);
        membership.addUser(resource, user);

        assertThat(memberIds(resource)).containsExactly(user.toString());
    }

    @Test
    void removeUserRemovesAndIsIdempotent() {
        UUID resource = globalResource("res", MemberType.USER);
        UUID user = globalUser();
        membership.addUser(resource, user);

        membership.removeUser(resource, user);
        assertThat(memberIds(resource)).isEmpty();

        membership.removeUser(resource, user); // no-op, no error
        assertThat(memberIds(resource)).isEmpty();
    }

    @Test
    void nameOfAndOrgIdOfResolveTheResource() {
        UUID resource = globalResource("Payroll", MemberType.USER);

        assertThat(membership.nameOf(resource)).contains("Payroll");
        assertThat(membership.orgIdOf(resource)).isEmpty(); // global resource
        assertThat(membership.nameOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void deletingAResourcePublishesResourceDeletedEvent() {
        UUID resource = globalResource("doomed", MemberType.USER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(Roles.ADMIN)))); // delete needs the admin authz

        adminService.delete(resource);

        assertThat(events.stream(ResourceDeletedEvent.class).map(ResourceDeletedEvent::resourceId))
                .contains(resource);
    }

    // --- helpers ---

    private UUID globalResource(String name, MemberType... allowed) {
        return orgContext.callAsPlatform(() -> {
            ResourceType type = types.save(new ResourceType("T-" + suffix(), null));
            for (MemberType memberType : allowed) {
                allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), memberType, null));
            }
            return resources.save(new Resource(name, type, null)).getId();
        });
    }

    private UUID globalUser() {
        String s = suffix();
        UUID id = users.createUser(new NewUser("u-" + s, "u-" + s + "@example.com", "U " + s,
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);
        return id;
    }

    private UUID orgUser(UUID org) {
        String s = suffix();
        UUID id = users.createUser(new NewUser("o-" + s, "o-" + s + "@example.com", "O " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), org).getId();
        createdUsers.add(id);
        return id;
    }

    private UUID newOrg() {
        UUID id = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization("org-" + suffix(), "org")).id());
        createdOrgs.add(id);
        return id;
    }

    private List<String> memberIds(UUID resourceId) {
        return orgContext.callAsPlatform(() -> memberRows.findByResourceId(resourceId).stream()
                .map(ResourceMemberRow::getMemberId).toList());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
