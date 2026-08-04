package com.example.sso.branding.internal.application;

import com.example.sso.branding.AuthScreen;
import com.example.sso.branding.ScreenCopy;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Screen wording on real Postgres, because the guarantees this table leans on are the DATABASE's and a mock
 * cannot hold them: the RLS policy that keeps one tenant's rows out of another's reads, and the tier-aware
 * partial unique index that makes "one row per screen per tier" true under a concurrent writer rather than
 * only under a check-then-act.
 *
 * <p>It also runs the resolve path through a real transaction, which the mocked unit tests structurally
 * cannot: those prove the merge arithmetic, this proves the rows the merge is fed actually come back scoped.
 */
class ScreenCopyTenantScopeIT extends AbstractIntegrationTest {

    @Autowired
    ScreenCopyService screenCopy;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        ownerJdbc().update("delete from auth_screen_copy where org_id is null");
        if (orgA != null) {
            organizations.delete(orgA);
        }
        if (orgB != null) {
            organizations.delete(orgB);
        }
    }

    private OrganizationView org(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug));
    }

    private ScreenCopy copy(String headline, String subtext, String footer) {
        return new ScreenCopy(headline, subtext, footer, null);
    }

    @Test
    void aTenantsWordingIsUsedForItAndAnotherTenantDoesNotSeeIt() {
        OrganizationView a = org("copy-a");
        OrganizationView b = org("copy-b");
        orgA = a.id();
        orgB = b.id();

        orgContext.runInOrg(orgA, () -> screenCopy.update(AuthScreen.LOGIN, copy("Sign in to Acme", null, null)));

        Map<AuthScreen, ScreenCopy> forA = orgContext.callInOrg(orgA, () -> screenCopy.resolve(orgA));
        Map<AuthScreen, ScreenCopy> forB = orgContext.callInOrg(orgB, () -> screenCopy.resolve(orgB));

        assertThat(forA.get(AuthScreen.LOGIN).headline()).isEqualTo("Sign in to Acme");
        assertThat(forB).isEmpty();
    }

    /**
     * Per-field inheritance across the tier boundary, against real rows. The unit test proves the merge; this
     * proves the platform row is actually READABLE from inside a tenant's context — an RLS policy that hid it
     * would make every tenant silently lose the inherited wording, and only a real database can say.
     */
    @Test
    void aTenantInheritsThePlatformWordingFieldByField() {
        OrganizationView a = org("copy-inherit");
        orgA = a.id();

        orgContext.runAsPlatform(() ->
                screenCopy.update(AuthScreen.LOGIN, copy("Platform heading", "Platform subtext", "Need help?")));
        orgContext.runInOrg(orgA, () -> screenCopy.update(AuthScreen.LOGIN, copy("Sign in to Acme", null, null)));

        ScreenCopy login = orgContext.callInOrg(orgA, () -> screenCopy.resolve(orgA)).get(AuthScreen.LOGIN);

        assertThat(login.headline()).isEqualTo("Sign in to Acme");
        assertThat(login.subtext()).isEqualTo("Platform subtext");
        assertThat(login.footer()).isEqualTo("Need help?");
    }

    /** Writing the same screen twice must UPDATE the tier's row, never accumulate a second one. */
    @Test
    void writingTheSameScreenTwiceKeepsOneRow() {
        OrganizationView a = org("copy-once");
        orgA = a.id();

        orgContext.runInOrg(orgA, () -> screenCopy.update(AuthScreen.LOGIN, copy("First", null, null)));
        orgContext.runInOrg(orgA, () -> screenCopy.update(AuthScreen.LOGIN, copy("Second", null, null)));

        Integer rows = ownerJdbc().queryForObject(
                "select count(*) from auth_screen_copy where org_id = ? and screen = 'LOGIN'",
                Integer.class, orgA);
        assertThat(rows).isEqualTo(1);
        assertThat(orgContext.callInOrg(orgA, () -> screenCopy.resolve(orgA))
                .get(AuthScreen.LOGIN).headline()).isEqualTo("Second");
    }

    /** Deleting one screen leaves the tier's other screens alone. */
    @Test
    void deletingOneScreenLeavesTheOthers() {
        OrganizationView a = org("copy-delete");
        orgA = a.id();

        orgContext.runInOrg(orgA, () -> {
            screenCopy.update(AuthScreen.LOGIN, copy("Sign in", null, null));
            screenCopy.update(AuthScreen.MFA, copy("Verify", null, null));
            screenCopy.delete(AuthScreen.LOGIN);
        });

        assertThat(orgContext.callInOrg(orgA, () -> screenCopy.resolve(orgA)))
                .containsOnlyKeys(AuthScreen.MFA);
    }

    /** Deleting the org takes its wording with it: the FK cascade, which no mock can demonstrate. */
    @Test
    void deletingTheOrgRemovesItsWording() {
        OrganizationView a = org("copy-cascade");
        UUID id = a.id();
        orgContext.runInOrg(id, () -> screenCopy.update(AuthScreen.LOGIN, copy("Sign in to Acme", null, null)));

        organizations.delete(id);

        Integer rows = ownerJdbc().queryForObject(
                "select count(*) from auth_screen_copy where org_id = ?", Integer.class, id);
        assertThat(rows).isZero();
    }
}
