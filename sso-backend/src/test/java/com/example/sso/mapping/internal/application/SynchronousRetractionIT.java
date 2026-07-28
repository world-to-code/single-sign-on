package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The synchronous retraction pass against a real database, and specifically the two things its unit test
 * structurally cannot see: that the membership is really gone, and that the audit row describing it only
 * exists if the transaction that made it committed.
 *
 * <p>That second half is the bug this shape exists for. {@code AuditEventWriter} persists in its OWN
 * transaction, so a row recorded inline outlives a rollback — and a re-evaluation CAN be rolled back after
 * retracting, since the last-administrator invariant runs at the end. The trail then asserts an authority
 * change that was undone, which is worse than no trail: an investigator reconstructing who lost which role
 * gets a confident wrong answer.
 */
class SynchronousRetractionIT extends AbstractIntegrationTest {

    @Autowired AttributeService attributes;
    @Autowired MappingRuleService mappingRules;
    @Autowired RoleService roles;
    @Autowired UserService users;
    @Autowired OrganizationService organizations;
    @Autowired UserGroupService groups;
    @Autowired OrgContext orgContext;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();
    private final List<UUID> createdOrgs = new ArrayList<>();
    private UUID superAdmin;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from mapping_rule_membership");
            ownerJdbc().update("delete from mapping_rule_condition");
            ownerJdbc().update("delete from mapping_rule");
            ownerJdbc().update("delete from audit_event where type like 'MAPPING_RULE_%'");
            createdUsers.forEach(id ->
                    ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdGroups.forEach(groups::delete);
            createdUsers.forEach(users::delete);
            createdRoles.forEach(roles::deleteRole);
        });
        createdOrgs.forEach(organizations::delete);
        createdUsers.clear();
        createdGroups.clear();
        createdRoles.clear();
        createdOrgs.clear();
        superAdmin = null;
    }

    @Test
    void aCommittedRetractionDropsTheRoleAndLeavesExactlyOneTrailRow() {
        UUID role = role();
        UUID subject = claimHolder(role, "employment", "contract");

        // ONE transaction, as the profile-switch caller has: the attribute deletion also fans out
        // asynchronously, and that pass is only released at commit — by which time this one has already run.
        orgContext.runAsPlatform(() -> inTransaction(status -> {
            attributes.remove(EntityKind.USER, subject.toString(), "employment");
            mappingRules.retractStaleClaims(subject);
        }));

        assertThat(orgContext.callAsPlatform(() -> roles.members(role)))
                .extracting(UserAccount::getId).doesNotContain(subject);
        assertThat(retractionRows(subject)).isEqualTo(1);
    }

    /**
     * And the rollback. Nothing changed, so nothing may claim to have changed — the row the retraction wrote
     * has to go with it. Recorded inline it would not: {@code REQUIRES_NEW} commits independently of the
     * transaction it is nested in, which is exactly right for a refusal and exactly wrong for a change.
     */
    @Test
    void aRolledBackRetractionLeavesNoTrailAndNoMembershipChange() {
        UUID role = role();
        UUID subject = claimHolder(role, "employment", "contract");

        orgContext.runAsPlatform(() -> inTransaction(status -> {
            attributes.remove(EntityKind.USER, subject.toString(), "employment");
            mappingRules.retractStaleClaims(subject);
            status.setRollbackOnly();
        }));

        assertThat(orgContext.callAsPlatform(() -> roles.members(role)))
                .extracting(UserAccount::getId).contains(subject);
        assertThat(retractionRows(subject)).isZero();
    }

    private void inTransaction(Consumer<TransactionStatus> work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(work);
    }

    /**
     * The tier the production caller actually runs in. Every other test here stands at PLATFORM, where the
     * RLS policy on {@code mapping_rule_membership} (FORCE ROW LEVEL SECURITY, V95) is permissive — so the one
     * failure mode they cannot see is the one that matters: if the claim rows were invisible to the acting
     * tier, {@code claimedRulesOf} returns empty, the pass returns immediately, and the profile move reports
     * success while the role is still held and the sessions are terminated anyway.
     */
    @Test
    void aRetractionInsideATenantTierSeesItsOwnClaimsAndDropsTheRole() {
        UUID org = org();
        UUID role = roleIn(org);
        UUID subject = claimHolderIn(org, role, "employment", "contract");

        orgContext.runInOrg(org, () -> inTransaction(status -> {
            attributes.remove(EntityKind.USER, subject.toString(), "employment");
            mappingRules.retractStaleClaims(subject);
        }));

        assertThat(orgContext.callAsPlatform(() -> roles.members(role)))
                .extracting(UserAccount::getId).doesNotContain(subject);
    }

    /**
     * The fixed point, against a real database. Losing the GROUP takes back the attributes that group LENT the
     * user, and the ROLE rule was matching on one of them — so a single pass over a snapshot of the attribute
     * set retracts the group and leaves the role behind. The unit test proves the loop runs; only this proves
     * the second round actually SEES the deleted membership (the delete is a @Modifying JPQL statement and the
     * re-read is a scalar projection, neither of which the mock can vouch for).
     */
    @Test
    void losingAGroupRetractsTheRoleThatDependedOnWhatTheGroupLent() {
        UUID role = role();
        UUID group = group();
        UUID subject = user();
        asSuper();
        try {
            orgContext.runAsPlatform(() -> {
                attributes.set(EntityKind.GROUP, group.toString(), "dept", "eng");
                attributes.set(EntityKind.USER, subject.toString(), "level", "staff");
                mappingRules.create(MappingRuleSpec.single("level", AttributeOperator.EQUALS, "staff",
                        MappingTargetKind.GROUP, group));
                // Created second, so the user is already IN the group and inherits dept=eng from it.
                mappingRules.create(MappingRuleSpec.single("dept", AttributeOperator.EQUALS, "eng",
                        MappingTargetKind.ROLE, role));
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(orgContext.callAsPlatform(() -> roles.members(role)))
                .extracting(UserAccount::getId).contains(subject); // the chain is real before we break it

        orgContext.runAsPlatform(() -> inTransaction(status -> {
            attributes.remove(EntityKind.USER, subject.toString(), "level");
            mappingRules.retractStaleClaims(subject);
        }));

        assertThat(orgContext.callAsPlatform(() -> groups.memberIdsOf(Set.of(group)))).doesNotContain(subject);
        assertThat(orgContext.callAsPlatform(() -> roles.members(role)))
                .extracting(UserAccount::getId).doesNotContain(subject);
    }

    /**
     * The last-administrator guard, driven through its REAL reads rather than a stubbed port.
     *
     * <p>Its narrowing asks {@code roleService.effectivePermissionNames(retractedRoleIds)} for
     * {@code user:update}, and that read is RLS-scoped to the acting tier. If it came back empty — a wrong
     * tier, a role row the policy hides — the narrowing answers "this retraction cannot touch admin
     * capability", the guard is skipped, and the tenant silently loses its last effective administrator with
     * no 409 and no way back except a platform super. Every unit test of that narrowing stubs the call, so all
     * of them pass whether the read works or not; this one executes it. (The name-versus-capability axis is
     * the unit test's job — {@code LastAdminInvariantAdapterTest}.)
     */
    @Test
    void aRetractionThatWouldStripTheTiersLastAdminIsRefusedAgainstRealReads() {
        UUID org = org();
        UUID orgAdmin = orgContext.callInOrg(org, () -> roles.findByName(Roles.ORG_ADMIN, org).orElseThrow().getId());
        // A fresh org provisions its own ROLE_ORG_ADMIN with nobody in it, so the mapping rule below makes this
        // person the tier's ONLY administrator — held by an attribute, which is the whole exposure.
        UUID subject = claimHolderIn(org, orgAdmin, "level", "lead");

        assertThatThrownBy(() -> orgContext.runInOrg(org, () -> inTransaction(status -> {
            attributes.remove(EntityKind.USER, subject.toString(), "level");
            mappingRules.retractStaleClaims(subject);
        }))).isInstanceOf(ConflictException.class).hasMessage("admin.lastAdmin");

        // Rolled back, so the person keeps a role they no longer qualify for — visible and fixable — rather
        // than the tier having no administrator, which only a platform super can undo.
        assertThat(orgContext.callAsPlatform(() -> roles.members(orgAdmin)))
                .extracting(UserAccount::getId).contains(subject);
    }

    private int retractionRows(UUID subject) {
        Integer count = ownerJdbc().queryForObject(
                "select count(*) from audit_event where type = 'MAPPING_RULE_RETRACTED' and subject_id = ?",
                Integer.class, subject.toString());
        return count == null ? 0 : count;
    }

    // --- fixture ---

    /** A user carrying {@code key=value}, with the rule that confers {@code role} on it already materialized. */
    private UUID claimHolder(UUID role, String key, String value) {
        UUID subject = user();
        asSuper();
        try {
            orgContext.runAsPlatform(() -> {
                attributes.set(EntityKind.USER, subject.toString(), key, value);
                // Created AFTER the attribute, so the cohort pass materializes the claim on the way in.
                mappingRules.create(MappingRuleSpec.single(key, AttributeOperator.EQUALS, value,
                        MappingTargetKind.ROLE, role));
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(orgContext.callAsPlatform(() -> roles.members(role)))
                .extracting(UserAccount::getId).contains(subject); // the fixture is not vacuous
        return subject;
    }

    private UUID roleIn(UUID org) {
        asSuper();
        try {
            // A tenant-grantable permission: org:create is PLATFORM-only and a tenant tier refuses to confer
            // it — correctly, and irrelevantly here, since this test is about claim VISIBILITY not authority.
            UUID id = orgContext.callInOrg(org, () ->
                    roles.create("ROLE_SRT_" + suffix().toUpperCase(), Set.of(Permissions.USER_READ)).getId());
            createdRoles.add(id);
            return id;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** The tenant-tier twin of {@link #claimHolder}: user, attribute and rule all owned by {@code org}. */
    private UUID claimHolderIn(UUID org, UUID role, String key, String value) {
        String s = suffix();
        String username = "srtt-" + s;
        UUID subject = orgContext.callInOrg(org, () -> users.createUser(new NewUser(username,
                username + "@example.com", "T " + s, "S3cret!pw9", Set.of("ROLE_USER")), org).getId());
        createdUsers.add(subject);
        asSuper();
        try {
            orgContext.runInOrg(org, () -> {
                attributes.set(EntityKind.USER, subject.toString(), key, value);
                mappingRules.create(MappingRuleSpec.single(key, AttributeOperator.EQUALS, value,
                        MappingTargetKind.ROLE, role));
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(orgContext.callAsPlatform(() -> roles.members(role)))
                .extracting(UserAccount::getId).contains(subject); // the fixture is not vacuous
        return subject;
    }

    private UUID group() {
        asSuper();
        try {
            UUID id = orgContext.callAsPlatform(() -> UUID.fromString(
                    groups.create(new GroupSpec("srtg-" + suffix(), null, null, Set.of())).id()));
            createdGroups.add(id);
            return id;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID org() {
        String slug = "srt-" + suffix();
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        createdOrgs.add(id);
        return id;
    }

    private UUID role() {
        asSuper();
        try {
            UUID id = orgContext.callAsPlatform(() ->
                    roles.create("ROLE_SRT_" + suffix().toUpperCase(), Set.of(Permissions.ORG_CREATE)).getId());
            createdRoles.add(id);
            return id;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID user() {
        String s = suffix();
        String username = "srt-" + s;
        UUID id = users.createUser(new NewUser(username, username + "@example.com", "S " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), null).getId();
        createdUsers.add(id);
        return id;
    }

    /** A REAL global ROLE_ADMIN, not just a context with the authority string: the grant ceiling resolves the
     *  actor's effective permissions from the database, so a principal with no row there can grant nothing. */
    private void asSuper() {
        if (superAdmin == null) {
            superAdmin = user();
            orgContext.runAsPlatform(() -> roles.addMember(roles.getOrCreate(Roles.ADMIN).getId(), superAdmin));
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                users.findById(superAdmin).orElseThrow().getUsername(), null,
                List.of(new SimpleGrantedAuthority(Roles.ADMIN))));
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
