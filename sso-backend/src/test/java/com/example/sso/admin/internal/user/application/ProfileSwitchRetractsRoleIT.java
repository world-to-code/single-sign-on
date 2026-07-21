package com.example.sso.admin.internal.user.application;

import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeDefinitionSpec;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The consequence a profile switch is really for: an attribute it deletes may be what granted a role.
 *
 * <p>This was the explicit requirement behind the preview/confirm design — an administrator must see that a
 * move changes authorization, not just data. Nothing asserted it until now, so a switch that deleted the
 * attribute rows without the ABAC re-evaluation ever running would have looked entirely correct: the user
 * would simply keep a role whose condition no longer holds.
 */
class ProfileSwitchRetractsRoleIT extends AbstractIntegrationTest {

    @Autowired UserProfileService userProfiles;
    @Autowired MappingRuleService rules;
    @Autowired RoleService roles;
    @Autowired ProfileService profiles;
    @Autowired AttributeDefinitionService definitions;
    @Autowired AttributeService attributes;
    @Autowired UserService users;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private UUID orgA;
    private UUID userId;

    @AfterEach
    void tearDown() {
        if (userId != null) {
            orgContext.runInOrg(orgA, () -> users.delete(userId));
        }
        if (orgA != null) {
            organizations.delete(orgA);
        }
    }

    private Profile tenantProfile() {
        return orgContext.callInOrg(orgA, () -> profiles.list()).stream()
                .filter(p -> p.kind() == ProfileKind.TENANT).findFirst().orElseThrow();
    }

    private Set<String> rolesOf() {
        return orgContext.callInOrg(orgA, () -> users.findById(userId).orElseThrow().getRoles().stream()
                .map(RoleRef::getName).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void deletingTheAttributeThatGrantedARoleRetractsIt() {
        String slug = "retract-it-" + UUID.randomUUID().toString().substring(0, 8);
        orgA = organizations.create(new NewOrganization(slug, slug)).id();
        await().until(() -> !orgContext.callInOrg(orgA, () -> profiles.list()).isEmpty());
        Profile tenant = tenantProfile();

        UUID roleId = orgContext.callInOrg(orgA, () -> roles.create("ROLE_PLATFORM_" + slug)).getId();
        orgContext.runInOrg(orgA, () -> {
            definitions.save(tenant.id(), new AttributeDefinitionSpec(EntityKind.USER, "team", "Team", null,
                    AttributeDataType.STRING, null, false, false, AttributeSource.LOCAL, 0));
            rules.create(MappingRuleSpec.single("team", AttributeOperator.EQUALS, "Platform",
                    MappingTargetKind.ROLE, roleId));
        });

        String name = "retract-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount created = orgContext.callInOrg(orgA, () -> users.createUser(
                new NewUser(name, name + "@example.com", "U", "S3cret!pw", Set.of(Roles.USER)), orgA));
        userId = created.getId();

        // The attribute grants the role through the mapping rule.
        orgContext.runInOrg(orgA,
                () -> attributes.set(EntityKind.USER, userId.toString(), "team", "Platform"));
        await().until(() -> rolesOf().stream().anyMatch(r -> r.startsWith("ROLE_PLATFORM_")));

        // Moving onto a profile that does not declare `team` deletes it — and the grant must go with it.
        Profile bare = tenantProfile();
        orgContext.runInOrg(orgA, () -> definitions.delete(
                orgContext.callInOrg(orgA, () -> definitions.definitionsIn(bare.id())).stream()
                        .filter(d -> d.key().equals("team")).findFirst().orElseThrow().id()));
        orgContext.runInOrg(orgA, () -> userProfiles.switchTo(userId, bare.id()));

        await().until(() -> rolesOf().stream().noneMatch(r -> r.startsWith("ROLE_PLATFORM_")));
        assertThat(rolesOf()).doesNotContain("ROLE_PLATFORM_" + slug);
    }
}
