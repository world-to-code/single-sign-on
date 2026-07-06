package com.example.sso.resource;

import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceMember;
import com.example.sso.resource.internal.domain.ResourceMemberRow;
import com.example.sso.resource.internal.domain.ResourceMemberRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.saml.RelyingPartyRequest;
import com.example.sso.saml.SamlRelyingPartyAdminService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The polymorphic {@code resource_member} has no FK, so deleting a member target (user/group/app) must
 * drop its membership rows via the {@code *DeletedEvent} → cleanup-listener path. Without it those rows
 * dangle and keep feeding the authorization ports a stale id.
 */
class ResourceMemberCleanupIT extends AbstractIntegrationTest {

    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    ResourceMemberRowRepository memberRows;
    @Autowired
    ResourceTypeAllowedMemberRepository allowedMembers;
    @Autowired
    UserService userService;
    @Autowired
    UserGroupService userGroups;
    @Autowired
    SamlRelyingPartyAdminService relyingParties;

    private UUID resourceId;

    @AfterEach
    void cleanup() {
        resources.deleteAll();
        types.deleteAll();
    }

    @Test
    void deletingAUserDropsItsMembershipRows() {
        UUID user = userService.createUser(new NewUser("clean-user", "clean-user@example.com", "clean-user",
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        seedResourceWith(ResourceMember.user(user));
        assertThat(members(MemberType.USER)).containsExactly(user.toString());

        userService.delete(user);

        assertThat(members(MemberType.USER)).isEmpty();
    }

    @Test
    void deletingAGroupDropsItsMembershipRows() {
        UUID group = UUID.fromString(userGroups.create(new GroupSpec("Clean-Group", null, null, Set.of())).id());
        seedResourceWith(ResourceMember.group(group));
        assertThat(members(MemberType.GROUP)).containsExactly(group.toString());

        userGroups.delete(group);

        assertThat(members(MemberType.GROUP)).isEmpty();
    }

    @Test
    void deletingAnApplicationDropsItsMembershipRows() {
        String appId = relyingParties.create(new RelyingPartyRequest("urn:test:clean-sp", null, "https://sp/acs", null,
                false, false, false, null, null, null, false, false, null, null, null, null, null)).id();
        seedResourceWith(ResourceMember.application(appId));
        assertThat(members(MemberType.APPLICATION)).containsExactly(appId);

        relyingParties.delete(UUID.fromString(appId));

        assertThat(members(MemberType.APPLICATION)).isEmpty();
    }

    private void seedResourceWith(ResourceMember member) {
        ResourceType any = saveType("CLEAN-ANY", MemberType.GROUP, MemberType.APPLICATION, MemberType.USER);
        resourceId = resources.save(new Resource("Clean-Res", any, null)).getId();
        memberRows.save(ResourceMemberRow.of(resourceId, member, null));
    }

    private ResourceType saveType(String name, MemberType... allowed) {
        ResourceType type = types.save(new ResourceType(name));
        for (MemberType memberType : allowed) {
            allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), memberType));
        }
        return type;
    }

    private Set<String> members(MemberType type) {
        return resources.findMemberIds(Set.of(resourceId), type.name());
    }
}
