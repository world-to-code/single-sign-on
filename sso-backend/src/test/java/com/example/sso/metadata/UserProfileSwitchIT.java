package com.example.sso.metadata;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Moving a user between profiles.
 *
 * <p>A profile decides which attributes a person HAS, so the move deletes whatever the target does not
 * declare. The preview exists because those keys can be conditions on mapping rules and policy bindings —
 * deleting one can retract a role — so an administrator has to see the cost before paying it.
 */
class UserProfileSwitchIT extends AbstractIntegrationTest {

    @Autowired UserProfileService userProfiles;
    @Autowired ProfileService profiles;
    @Autowired AttributeDefinitionService definitions;
    @Autowired AttributeService attributes;
    @Autowired UserService users;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private UUID orgA;
    private final List<java.util.Map.Entry<UUID, UUID>> createdUsers = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        createdUsers.forEach(entry -> orgContext.runInOrg(entry.getValue(), () -> users.delete(entry.getKey())));
        createdUsers.clear();
        if (orgA != null) {
            organizations.delete(orgA);
        }
    }

    private UUID org() {
        String slug = "switch-it-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        await().until(() -> !orgContext.callInOrg(id, () -> profiles.list()).isEmpty());
        return id;
    }

    private Profile tenantProfile(UUID orgId) {
        return orgContext.callInOrg(orgId, () -> profiles.list()).stream()
                .filter(p -> p.kind() == ProfileKind.TENANT).findFirst().orElseThrow();
    }

    private AttributeDefinitionSpec spec(String key) {
        return new AttributeDefinitionSpec(EntityKind.USER, key, key, null, AttributeDataType.STRING, null,
                false, false, AttributeSource.LOCAL, 0);
    }

    private UserAccount user(UUID orgId) {
        String name = "switch-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount created = orgContext.callInOrg(orgId,
                () -> users.createUser(new NewUser(name, name + "@example.com", "U", "S3cret!pw",
                        Set.of("ROLE_USER")), orgId));
        createdUsers.add(java.util.Map.entry(created.getId(), orgId));
        return created;
    }

    /** A second profile the tenant made, declaring a different set of attributes. */
    private UUID secondProfile(UUID orgId, String declaring) {
        Profile tenant = tenantProfile(orgId);
        orgContext.runInOrg(orgId, () -> definitions.save(tenant.id(), spec(declaring)));
        return tenant.id();
    }

    @Test
    void previewNamesExactlyTheAttributesTheTargetDoesNotDeclare() {
        orgA = org();
        Profile tenant = tenantProfile(orgA);
        UserAccount subject = user(orgA);
        orgContext.runInOrg(orgA, () -> {
            definitions.save(tenant.id(), spec("team"));
            attributes.set(EntityKind.USER, subject.getId().toString(), "team", "Platform");
            attributes.set(EntityKind.USER, subject.getId().toString(), "legacyCode", "X1");
        });

        ProfileSwitchPreview preview =
                orgContext.callInOrg(orgA, () -> userProfiles.preview(subject.getId(), tenant.id()));

        // `team` is declared here and survives; `legacyCode` is not and would go.
        assertThat(preview.removedKeys()).containsExactly("legacyCode");
        assertThat(preview.isLossless()).isFalse();
    }

    /** The preview writes nothing — an administrator can look without paying. */
    @Test
    void previewingDeletesNothing() {
        orgA = org();
        Profile tenant = tenantProfile(orgA);
        UserAccount subject = user(orgA);
        orgContext.runInOrg(orgA, () -> {
            attributes.set(EntityKind.USER, subject.getId().toString(), "legacyCode", "X1");
        });

        orgContext.callInOrg(orgA, () -> userProfiles.preview(subject.getId(), tenant.id()));

        assertThat(orgContext.callInOrg(orgA,
                () -> attributes.attributesOfInTier(EntityKind.USER, subject.getId().toString())))
                .extracting(Attribute::key).contains("legacyCode");
    }

    @Test
    void switchingDeletesWhatTheTargetDoesNotDeclareAndKeepsWhatItDoes() {
        orgA = org();
        Profile tenant = tenantProfile(orgA);
        UserAccount subject = user(orgA);
        orgContext.runInOrg(orgA, () -> {
            definitions.save(tenant.id(), spec("team"));
            attributes.set(EntityKind.USER, subject.getId().toString(), "team", "Platform");
            attributes.set(EntityKind.USER, subject.getId().toString(), "legacyCode", "X1");
        });

        orgContext.runInOrg(orgA, () -> userProfiles.switchTo(subject.getId(), tenant.id()));

        List<Attribute> remaining = orgContext.callInOrg(orgA,
                () -> attributes.attributesOfInTier(EntityKind.USER, subject.getId().toString()));
        assertThat(remaining).extracting(Attribute::key).contains("team").doesNotContain("legacyCode");
        assertThat(orgContext.callInOrg(orgA, () -> users.findById(subject.getId()).orElseThrow())
                .getProfileId()).isEqualTo(tenant.id());
    }

    /**
     * A directory owns an externally-provisioned user's attributes. Moving them would delete values the next
     * sync simply rewrites — a fight the move cannot win, having done the damage in the meantime.
     */
    @Test
    void anExternallyProvisionedUserCannotBeMoved() {
        orgA = org();
        Profile tenant = tenantProfile(orgA);
        UserAccount subject = user(orgA);
        orgContext.runInOrg(orgA, () -> {
            users.assignExternalId(subject.getId(), "dir-" + subject.getId());
        });

        assertThatThrownBy(() -> orgContext.runInOrg(orgA,
                () -> userProfiles.switchTo(subject.getId(), tenant.id())))
                .isInstanceOf(ConflictException.class);
    }

    /**
     * Built-ins are app_user columns, so a move must never propose deleting one. The case is only reachable
     * through a stray tag carrying a base key — a tenant could declare `email` as a free-form attribute before
     * profiles existed, and V126 carried those rows forward. Deleting it here would read to an administrator
     * as "this move removes their email".
     */
    @Test
    void aMoveNeverProposesDeletingABuiltInKey() {
        orgA = org();
        Profile tenant = tenantProfile(orgA);
        UserAccount subject = user(orgA);
        orgContext.runInOrg(orgA, () ->
                attributes.set(EntityKind.USER, subject.getId().toString(), "email", "stray@example.com"));

        ProfileSwitchPreview preview =
                orgContext.callInOrg(orgA, () -> userProfiles.preview(subject.getId(), tenant.id()));

        assertThat(preview.removedKeys()).doesNotContain("email");
    }
}
