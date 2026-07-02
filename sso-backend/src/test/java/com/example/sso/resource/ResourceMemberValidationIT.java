package com.example.sso.resource;

import com.example.sso.resource.internal.application.ResourceAdminService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
    UserService users;

    private final List<UUID> createdUsers = new ArrayList<>();
    private UUID resourceId;

    @BeforeEach
    void setUp() {
        ResourceType any = types.save(new ResourceType("VAL-ANY",
                Set.of(MemberType.GROUP, MemberType.USER, MemberType.APPLICATION)));
        resourceId = resources.save(new Resource("Val-Res", any)).getId();
    }

    @AfterEach
    void cleanup() {
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

        Resource after = resources.findByIdForAdminView(resourceId).orElseThrow();
        assertThat(after.getMembers().stream().map(m -> m.memberId())).containsExactly(id.toString());
    }
}
