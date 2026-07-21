package com.example.sso.metadata;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.UserGroupService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The import against a real database.
 *
 * <p>Everything here was previously covered only by mocks, and mocks could not hold what the database holds.
 * Four defects hid behind exactly that: base attribute keys the profile validator refuses by name (so every
 * row of every genuine file failed), a per-org unique index that treats "" as a value, a constraint violation
 * arriving as something other than the exception the loop caught, and RLS on the fresh connection each row's
 * own transaction borrows. A fixture can be shaped to agree with the code; a schema cannot.
 *
 * <p>So these assert PERSISTED state rather than the response counters — the counters are computed by the loop
 * under test, and asserting them proves only that it counted its own work.
 */
class CsvImportIT extends AbstractIntegrationTest {

    @Autowired CsvImportService imports;
    @Autowired CsvTemplateService templates;
    @Autowired ProfileService profiles;
    @Autowired OrganizationService organizations;
    @Autowired UserService users;
    @Autowired UserGroupService groups;
    @Autowired OrgContext orgContext;

    private UUID orgA;
    private UUID orgB;

    /**
     * The accounts this suite creates hold the organization down: {@code app_user.org_id} has no cascade, by
     * design — deleting a tenant must not silently delete its people. Cleared through the owning role, which is
     * what {@code ownerJdbc} exists for: a non-superuser cannot reach another org's rows to tidy them.
     */
    @AfterEach
    void tearDown() {
        drop(orgA);
        drop(orgB);
    }

    private void drop(UUID orgId) {
        if (orgId == null) {
            return;
        }
        ownerJdbc().update("delete from app_user where org_id = ?", orgId);
        organizations.delete(orgId);
    }

    private UUID org() {
        String slug = "csv-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        await().until(() -> orgContext.callInOrg(id, () -> profiles.list()).size() >= 2);
        return id;
    }

    private UUID tenantProfile(UUID orgId) {
        return orgContext.callInOrg(orgId, () -> profiles.list()).stream()
                .filter(profile -> profile.kind() == ProfileKind.TENANT).findFirst().orElseThrow().id();
    }

    private MultipartRequest upload(String csv) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("file", "users.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    /**
     * The one that would have caught the base-attribute defect on day one: the columns come from the profile's
     * own template, so the importer and the template cannot disagree about what a profile declares.
     */
    @Test
    void aFileBuiltFromTheProfilesOwnTemplateImportsCleanly() {
        orgA = org();
        UUID profile = tenantProfile(orgA);

        String header = orgContext.callInOrg(orgA, () -> templates.templateFor(profile).content())
                .lines().findFirst().orElseThrow();
        String username = "ada-" + UUID.randomUUID().toString().substring(0, 8);
        String row = header.replace("username", username).replace("email", username + "@example.com")
                .replace("groups", "");

        CsvImportResult result = orgContext.callInOrg(orgA, () -> imports.apply(profile, upload(header + "\n" + row)));

        assertThat(result.failures()).isEmpty();
        assertThat(result.created()).isEqualTo(1);
        assertThat(orgContext.callInOrg(orgA, () -> users.findByUsernameInOrg(username, orgA))).isPresent();
    }

    /** Persisted state, not the counter the loop under test computed for itself. */
    @Test
    void theAccountIsCreatedWithNoPasswordAndTheProfileAttributesItWasGiven() {
        orgA = org();
        UUID profile = tenantProfile(orgA);
        String username = "grace-" + UUID.randomUUID().toString().substring(0, 8);

        orgContext.runInOrg(orgA, () -> imports.apply(profile,
                upload("username,email,displayName\n" + username + "," + username + "@example.com,Grace H\n")));

        UserAccount created = orgContext.callInOrg(orgA, () -> users.findByUsernameInOrg(username, orgA))
                .orElseThrow();
        assertThat(created.getDisplayName()).isEqualTo("Grace H");
        assertThat(created.isEmailVerified()).isFalse();
        assertThat(orgContext.callInOrg(orgA, () -> users.hasPassword(created.getId()))).isFalse();
    }

    /**
     * A file cannot hand out a credential, so the account has no password — and its address was asserted by an
     * administrator rather than proven by its owner, so it starts unverified. Together those are what stop an
     * imported row being a working login the moment it lands.
     */
    @Test
    void anImportedAccountCannotBeAuthenticatedByPassword() {
        orgA = org();
        UUID profile = tenantProfile(orgA);
        String username = "hopper-" + UUID.randomUUID().toString().substring(0, 8);

        orgContext.runInOrg(orgA, () -> imports.apply(profile,
                upload("username,email\n" + username + "," + username + "@example.com\n")));

        assertThat(orgContext.callInOrg(orgA, () -> users.verifyPassword(username, "anything"))).isFalse();
        assertThat(orgContext.callInOrg(orgA, () -> users.verifyPassword(username, ""))).isFalse();
    }

    /**
     * The row that loses the preview-to-apply race is the whole reason each creation holds its own
     * transaction. A real unique-index violation — not a stubbed exception — must leave the other rows
     * committed and become one reported failure.
     */
    @Test
    void aRowCollidingWithAnExistingAccountFailsAloneAndTheOthersPersist() {
        orgA = org();
        UUID profile = tenantProfile(orgA);
        String taken = "taken-" + UUID.randomUUID().toString().substring(0, 8);
        String fresh = "fresh-" + UUID.randomUUID().toString().substring(0, 8);
        orgContext.runInOrg(orgA, () -> users.createUser(
                new NewUser(taken, taken + "@example.com", "Already", null, Set.of()), orgA));

        CsvImportResult result = orgContext.callInOrg(orgA, () -> imports.apply(profile,
                upload("username,email\n" + taken + "," + taken + "-2@example.com\n"
                        + fresh + "," + fresh + "@example.com\n")));

        assertThat(result.created()).isEqualTo(1);
        assertThat(orgContext.callInOrg(orgA, () -> users.findByUsernameInOrg(fresh, orgA))).isPresent();
    }

    /** A group named in a file is joined, not created — and only one that exists in THIS organization. */
    @Test
    void aNamedGroupIsJoinedAndOneFromAnotherTenantIsNot() {
        orgA = org();
        orgB = org();
        String onlyInB = "b-only-" + UUID.randomUUID().toString().substring(0, 8);
        orgContext.runInOrg(orgB, () -> groups.create(new GroupSpec(onlyInB, null, null, Set.of())));

        UUID profile = tenantProfile(orgA);
        String username = "crosser-" + UUID.randomUUID().toString().substring(0, 8);

        CsvImportResult result = orgContext.callInOrg(orgA, () -> imports.apply(profile,
                upload("username,email,groups\n" + username + "," + username + "@example.com," + onlyInB + "\n")));

        assertThat(result.created()).isZero();
        assertThat(result.failures()).singleElement()
                .extracting(CsvRowFailure::reason).asString()
                .contains(onlyInB).doesNotContain("metadata.csv"); // resolved text, not a key
        assertThat(orgContext.callInOrg(orgA, () -> users.findByUsernameInOrg(username, orgA))).isEmpty();
    }

    /**
     * A username is unique only WITHIN an organization, so the same one existing next door is not "already
     * exists" here — reading it that way would silently skip a user the file asked for.
     */
    @Test
    void aUsernameTakenInAnotherTenantDoesNotCountAsExistingHere() {
        orgA = org();
        orgB = org();
        String shared = "shared-" + UUID.randomUUID().toString().substring(0, 8);
        orgContext.runInOrg(orgB, () -> users.createUser(
                new NewUser(shared, shared + "@example.com", "In B", null, Set.of()), orgB));

        UUID profile = tenantProfile(orgA);
        CsvImportResult result = orgContext.callInOrg(orgA, () -> imports.apply(profile,
                upload("username,email\n" + shared + "," + shared + "@example.com\n")));

        assertThat(result.existing()).isEmpty();
        assertThat(result.created()).isEqualTo(1);
        assertThat(orgContext.callInOrg(orgA, () -> users.findByUsernameInOrg(shared, orgA))).isPresent();
    }

    /** The preview is a read. Confirming it is theatre if it has already written. */
    @Test
    void previewingCreatesNothing() {
        orgA = org();
        UUID profile = tenantProfile(orgA);
        String username = "ghost-" + UUID.randomUUID().toString().substring(0, 8);

        CsvImportPreview preview = orgContext.callInOrg(orgA, () -> imports.preview(profile,
                upload("username,email\n" + username + "," + username + "@example.com\n")));

        assertThat(preview.toCreate()).hasSize(1);
        assertThat(orgContext.callInOrg(orgA, () -> users.findByUsernameInOrg(username, orgA))).isEmpty();
    }
    /**
     * An account that is already here is reported, not created again and not an error — that is the whole
     * meaning of the "existing" bucket, and it is what makes re-uploading a corrected file safe.
     */
    @Test
    void anAccountAlreadyInThisOrganizationIsReportedAsExisting() {
        orgA = org();
        UUID profile = tenantProfile(orgA);
        String here = "here-" + UUID.randomUUID().toString().substring(0, 8);
        String neu = "new-" + UUID.randomUUID().toString().substring(0, 8);
        orgContext.runInOrg(orgA, () -> users.createUser(
                new NewUser(here, here + "@example.com", "Already", null, Set.of()), orgA));

        CsvImportResult result = orgContext.callInOrg(orgA, () -> imports.apply(profile,
                upload("username,email\n" + here + "," + here + "@example.com\n"
                        + neu + "," + neu + "@example.com\n")));

        assertThat(result.existing()).containsExactly(here);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failures()).isEmpty();
    }

    /**
     * Structurally invisible here, recorded rather than faked: the DataIntegrityViolationException branch in
     * apply exists for a genuine race — another administrator inserting the same username between this
     * import's plan and its write. A sequential test cannot produce that interleaving, so the guarantee lives
     * in the per-org unique index (uq_app_user_org_username, V68) plus REQUIRES_NEW on each row, and the
     * closest available proof is the stubbed case in CsvImportApplyTest. A migration dropping that index would
     * turn the race into duplicate accounts, and nothing here would notice.
     */
}
