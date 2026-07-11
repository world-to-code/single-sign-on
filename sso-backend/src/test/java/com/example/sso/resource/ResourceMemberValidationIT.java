package com.example.sso.resource;

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
import com.example.sso.user.account.NewUser;
import com.example.sso.user.role.Roles;
import com.example.sso.user.account.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attach-time member validation: a referenced member must EXIST (keeps scope rows referentially clean
 * for later subtree enforcement), and a malformed id is the client's fault (400, not a 500 server error).
 */
class ResourceMemberValidationIT extends AbstractIntegrationTest {

    @Autowired
    ResourceAdminService service;
    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    ResourceMemberRowRepository memberRows;
    @Autowired
    ResourceTypeAllowedMemberRepository allowedMembers;
    @Autowired
    UserService users;

    private final List<UUID> createdUsers = new ArrayList<>();
    private UUID resourceId;

    @BeforeEach
    void setUp() {
        // Run as a super admin (unscoped): this suite exercises validation, not subtree scope.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(Roles.ADMIN))));
        ResourceType any = saveType("VAL-ANY", MemberType.GROUP, MemberType.USER, MemberType.APPLICATION);
        resourceId = resources.save(new Resource("Val-Res", any, null)).getId();
    }

    private ResourceType saveType(String name, MemberType... allowed) {
        ResourceType type = types.save(new ResourceType(name, null));
        for (MemberType memberType : allowed) {
            allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), memberType, null));
        }
        return type;
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        resources.deleteAll();
        types.deleteAll();
        createdUsers.forEach(users::delete);
        createdUsers.clear();
    }

    @Test
    void malformedMemberIdIsA400NotA500() {
        assertThatThrownBy(() -> service.attachMember(resourceId, MemberType.GROUP, "not-a-uuid"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.attachMember(resourceId, MemberType.USER, "42"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void nonexistentMemberIsRejected() {
        assertThatThrownBy(() -> service.attachMember(resourceId, MemberType.USER, UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.attachMember(resourceId, MemberType.APPLICATION, "ghost-client"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anExistingUserAttaches() {
        UUID id = users.createUser(new NewUser("val-user", "val-user@example.com", "val-user",
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);

        service.attachMember(resourceId, MemberType.USER, id.toString());

        assertThat(memberRows.findByResourceId(resourceId).stream().map(ResourceMemberRow::getMemberId))
                .containsExactly(id.toString());
    }
}
