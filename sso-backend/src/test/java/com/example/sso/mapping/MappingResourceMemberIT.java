package com.example.sso.mapping;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A RESOURCE_MEMBER mapping rule adds a matching user as a member of a resource (and retracts when the rule or
 * the user's match goes away), reusing the resource module's same-org / type-allows-USER integrity. A rule may
 * only target a resource in its own tier, and deleting the target resource drops its rules.
 */
class MappingResourceMemberIT extends AbstractIntegrationTest {

    @Autowired MappingRuleService mappingRules;
    @Autowired ResourceAdminService resourceAdmin;
    @Autowired ResourceRepository resources;
    @Autowired ResourceTypeRepository types;
    @Autowired ResourceTypeAllowedMemberRepository allowedMembers;
    @Autowired ResourceMemberRowRepository memberRows;
    @Autowired AttributeService attributes;
    @Autowired UserService users;
    @Autowired OrgContext orgContext;

    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from mapping_rule_membership");
            ownerJdbc().update("delete from mapping_rule");
            memberRows.deleteAll();
            resources.deleteAll();
            types.deleteAll();
            createdUsers.forEach(id -> ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdUsers.forEach(users::delete);
        });
        createdUsers.clear();
    }

    @Test
    void aResourceMemberRuleAddsThenRemovesTheMatchingUser() {
        UUID resource = globalResource("Payroll", MemberType.USER);
        UUID matching = globalUser("dept", "eng");

        MappingRuleView rule = orgContext.callAsPlatform(() ->
                mappingRules.create(MappingRuleSpec.single("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.RESOURCE_MEMBER, resource)));
        assertThat(isMember(resource, matching)).isTrue();

        orgContext.runAsPlatform(() -> mappingRules.delete(UUID.fromString(rule.id())));
        assertThat(isMember(resource, matching)).isFalse();
    }

    @Test
    void deletingTheTargetResourceDropsItsRules() {
        UUID resource = globalResource("doomed", MemberType.USER);
        globalUser("dept", "eng");
        MappingRuleView rule = orgContext.callAsPlatform(() ->
                mappingRules.create(MappingRuleSpec.single("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.RESOURCE_MEMBER, resource)));
        assertThat(rulesForTarget(resource)).isEqualTo(1);

        // delete needs the admin authz (super); the AFTER_COMMIT listener then drops the dangling rules.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(Roles.ADMIN))));
        orgContext.runAsPlatform(() -> resourceAdmin.delete(resource));

        assertThat(rulesForTarget(resource)).isZero();
        assertThat(provenanceCount(UUID.fromString(rule.id()))).isZero();
    }

    @Test
    void aRuleCannotTargetAResourceOutsideItsTier() {
        UUID resource = globalResource("global-res", MemberType.USER);
        // Make the resource org-scoped directly, so a platform-tier rule targeting it is out of tier.
        UUID org = orgContext.callAsPlatform(() -> UUID.fromString(
                ownerJdbc().queryForObject("select id from organization limit 1", String.class)));
        orgContext.runAsPlatform(() -> ownerJdbc().update("update resource set org_id = ? where id = ?", org, resource));

        orgContext.runAsPlatform(() ->
                assertThatThrownBy(() -> mappingRules.create(
                        MappingRuleSpec.single("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.RESOURCE_MEMBER, resource)))
                        .isInstanceOf(BadRequestException.class));
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

    private UUID globalUser(String key, String value) {
        String s = suffix();
        UUID id = users.createUser(new NewUser("u-" + s, "u-" + s + "@example.com", "U " + s,
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);
        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, id.toString(), key, value));
        return id;
    }

    private boolean isMember(UUID resourceId, UUID userId) {
        return orgContext.callAsPlatform(() -> memberRows.findByResourceId(resourceId).stream()
                .anyMatch(row -> row.getMemberId().equals(userId.toString())));
    }

    private int rulesForTarget(UUID targetId) {
        return count("select count(*) from mapping_rule where target_id = ?", targetId);
    }

    private int provenanceCount(UUID ruleId) {
        return count("select count(*) from mapping_rule_membership where rule_id = ?", ruleId);
    }

    private int count(String sql, UUID arg) {
        Integer n = orgContext.callAsPlatform(() -> ownerJdbc().queryForObject(sql, Integer.class, arg));
        return n == null ? 0 : n;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
