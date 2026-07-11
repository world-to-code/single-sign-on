package com.example.sso.resource;

import com.example.sso.resource.internal.application.ResourceAdminService;
import com.example.sso.resource.internal.application.ResourceDetailView;
import com.example.sso.resource.internal.application.ResourceGrantDetailView;
import com.example.sso.resource.internal.application.ResourceNodeView;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceGrant;
import com.example.sso.resource.internal.domain.ResourceGrantRow;
import com.example.sso.resource.internal.domain.ResourceGrantRowRepository;
import com.example.sso.resource.internal.domain.ResourceMember;
import com.example.sso.resource.internal.domain.ResourceMemberRow;
import com.example.sso.resource.internal.domain.ResourceMemberRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.saml.relyingparty.RelyingPartyRequest;
import com.example.sso.saml.relyingparty.SamlRelyingPartyAdminService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.NewUser;
import com.example.sso.user.Permissions;
import com.example.sso.user.Roles;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Phase 4 scoped-console detail read model: parents/children for DAG navigation and members/grants with
 * their display labels resolved, all confined to the actor's subtree.
 *
 * <pre>
 *   dev ─→ backend ─→ shared   (diamond: dev → shared directly too)
 *    └───→ frontend
 * </pre>
 */
class ResourceDetailIT extends AbstractIntegrationTest {

    @Autowired
    ResourceAdminService service;
    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    UserService userService;
    @Autowired
    UserGroupService userGroups;
    @Autowired
    SamlRelyingPartyAdminService relyingParties;
    @Autowired
    ResourceMemberRowRepository memberRows;
    @Autowired
    ResourceGrantRowRepository grantRows;
    @Autowired
    ResourceTypeAllowedMemberRepository allowedMembers;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();
    private final List<UUID> createdApps = new ArrayList<>();

    private UUID dev;
    private UUID backend;
    private UUID frontend;
    private UUID shared;
    private UUID backendLead;
    private UUID memberUser;
    private UUID viewerUser;
    private UUID backendGroup;
    private String appId;

    @BeforeEach
    void buildTree() {
        ResourceType any = saveType("DET-ANY",
                MemberType.RESOURCE, MemberType.GROUP, MemberType.APPLICATION, MemberType.USER);

        backendLead = user("det-backendlead");
        memberUser = user("det-memberuser");
        viewerUser = user("det-vieweruser");
        backendGroup = group("Det-Backend", memberUser);
        appId = relyingParties.create(new RelyingPartyRequest("urn:test:det-sp", null, "https://sp/acs", null,
                false, false, false, null, null, null, false, false, null, null, null, null, null)).id();
        createdApps.add(UUID.fromString(appId));

        dev = resources.save(new Resource("Det-Dev", any, null)).getId();
        backend = resources.save(new Resource("Det-Backend", any, null)).getId();
        frontend = resources.save(new Resource("Det-Frontend", any, null)).getId();
        shared = resources.save(new Resource("Det-Shared", any, null)).getId();

        grantRows.save(ResourceGrantRow.of(backend, ResourceGrant.admin(backendLead), null));
        grantRows.save(ResourceGrantRow.of(backend, ResourceGrant.viewer(viewerUser), null));
        memberRows.save(ResourceMemberRow.of(backend, ResourceMember.group(backendGroup), null));
        memberRows.save(ResourceMemberRow.of(backend, ResourceMember.user(memberUser), null));
        memberRows.save(ResourceMemberRow.of(backend, ResourceMember.application(appId), null));
        memberRows.save(ResourceMemberRow.of(backend, ResourceMember.application("ghost-app"), null)); // no such app → null label

        asRole(Roles.ADMIN, "admin");
        service.attachChild(dev, backend);
        service.attachChild(dev, frontend);
        service.attachChild(backend, shared);
        service.attachChild(dev, shared); // diamond
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        resources.deleteAll();
        types.deleteAll();
        createdGroups.forEach(userGroups::delete);
        createdGroups.clear();
        createdApps.forEach(relyingParties::delete);
        createdApps.clear();
        createdUsers.forEach(userService::delete);
        createdUsers.clear();
    }

    @Test
    void detailResolvesTheDiamondsParentsAndChildren() {
        asRole(Roles.ADMIN, "admin");

        assertThat(service.detail(shared).parents().stream().map(ResourceNodeView::id).map(UUID::fromString))
                .containsExactlyInAnyOrder(dev, backend); // both parents of the diamond
        assertThat(service.detail(backend).children().stream().map(ResourceNodeView::id).map(UUID::fromString))
                .containsExactly(shared);
        assertThat(service.detail(dev).parents()).isEmpty(); // a root
    }

    @Test
    void detailResolvesMemberAndGrantLabels() {
        asRole(Roles.ADMIN, "admin");
        ResourceDetailView view = service.detail(backend);

        assertThat(label(view, MemberType.GROUP, backendGroup.toString())).isEqualTo("Det-Backend");
        assertThat(label(view, MemberType.USER, memberUser.toString())).isEqualTo("det-memberuser");
        assertThat(label(view, MemberType.APPLICATION, appId)).isEqualTo("urn:test:det-sp");
        // A member whose target does not resolve carries a null label, not an error.
        assertThat(label(view, MemberType.APPLICATION, "ghost-app")).isNull();

        assertThat(view.grants()).extracting(ResourceGrantDetailView::username, ResourceGrantDetailView::tier)
                .containsExactlyInAnyOrder(
                        tuple("det-backendlead", "ADMIN"),
                        tuple("det-vieweruser", "VIEWER"));
    }

    @Test
    void detailIsConfinedToTheActorsSubtree() {
        asDelegate(backendLead);

        assertThatCode(() -> service.detail(backend)).doesNotThrowAnyException();
        assertThatCode(() -> service.detail(shared)).doesNotThrowAnyException(); // descendant, in subtree
        assertForbidden(() -> service.detail(frontend)); // sibling
        assertForbidden(() -> service.detail(dev));      // parent above the grant
        assertForbidden(() -> service.detail(UUID.randomUUID())); // unknown → 403, not 404 (no existence leak)
    }

    @Test
    void detailHidesAncestorsOutsideTheActorsSubtree() {
        // shared has two parents: backend (the delegate manages it) and dev (above their grant).
        // A super admin sees both; the delegate must see only the in-scope one — never learn about dev.
        asRole(Roles.ADMIN, "admin");
        assertThat(service.detail(shared).parents().stream().map(ResourceNodeView::id).map(UUID::fromString))
                .containsExactlyInAnyOrder(dev, backend);

        asDelegate(backendLead);
        assertThat(service.detail(shared).parents().stream().map(ResourceNodeView::id).map(UUID::fromString))
                .containsExactly(backend); // dev (out-of-scope ancestor) is hidden
    }

    private String label(ResourceDetailView view, MemberType type, String memberId) {
        return view.members().stream()
                .filter(member -> member.memberType().equals(type.name()) && member.memberId().equals(memberId))
                .findFirst().orElseThrow()
                .label();
    }

    private void assertForbidden(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ForbiddenException.class);
    }

    private void asDelegate(UUID userId) {
        String username = userService.findById(userId).orElseThrow().getUsername();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(Permissions.RESOURCE_READ))));
    }

    private void asRole(String role, String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(role))));
    }

    private ResourceType saveType(String name, MemberType... allowed) {
        ResourceType type = types.save(new ResourceType(name, null));
        for (MemberType memberType : allowed) {
            allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), memberType, null));
        }
        return type;
    }

    private UUID user(String username) {
        UUID id = userService.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);
        return id;
    }

    private UUID group(String name, UUID memberId) {
        UUID id = UUID.fromString(userGroups.create(new GroupSpec(name, null, null, Set.of(memberId))).id());
        createdGroups.add(id);
        return id;
    }
}
