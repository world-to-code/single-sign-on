package com.example.sso.metadata;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.role.Roles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The import's lost race — the window between planning a row and writing it.
 *
 * <p>{@code CsvImportServiceImpl.apply} plans first and writes second, so another administrator can take the
 * username in between. The check at plan time cannot prevent that; the guarantee is the per-org unique index
 * ({@code uq_app_user_org_username}, V68) plus {@code REQUIRES_NEW} on each row.
 *
 * <p>The window is opened DELIBERATELY rather than by racing threads: the other administrator's whole import
 * runs inside the first invocation of the creator, i.e. after this import planned and before it wrote. Two
 * threads on a barrier reproduce this only sometimes — the first draft of this test did exactly that, passed,
 * and then survived reverting both fixes it was written to pin. Everything below the timing is real: real
 * inserts, the real index, the real per-row transaction.
 *
 * <p>What this does NOT reach, stated so nobody reads more into it: the DB-level duplicate key. The competing
 * import COMMITS before this one writes, so {@code createUser}'s own existence check refuses the row first and
 * the constraint is never violated. The window that reaches the index is the one INSIDE {@code createUser},
 * between that check and the insert, and no hook here can hold it open — which is what the original note meant
 * by structurally invisible. A racing-threads draft did hit it once, and what it exposed lives in
 * {@code OrgContextRestoreTest}: the GUC restore in {@code OrgContext.withState}'s finally threw over the
 * duplicate key, so the caller got "current transaction is aborted", which matches no {@code catch} in the
 * loop and 500'd the whole import — the exact failure this loop's comment claims to have fixed.
 */
class CsvImportConcurrencyIT extends AbstractIntegrationTest {

    @Autowired CsvImportService imports;
    @Autowired CsvTemplateService templates;
    @Autowired ProfileService profiles;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;
    @MockitoSpyBean CsvUserCreator creator;

    private UUID org;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (org != null) {
            // app_user.org_id has no cascade by design — a tenant's people outlive its deletion.
            ownerJdbc().update("delete from app_user where org_id = ?", org);
            organizations.delete(org);
        }
    }

    /** The loser reports one row failure and still returns its report, rather than 500ing the request. */
    @Test
    void aUsernameTakenBetweenPlanAndWriteFailsThatRowAlone() {
        org = organization();
        UUID profile = tenantProfile(org);
        String contested = "ada-" + UUID.randomUUID().toString().substring(0, 8);
        String mine = "alan-" + UUID.randomUUID().toString().substring(0, 8);

        // The other administrator wins the name between this import's plan and its write.
        theOtherAdministratorImports(profile, fileFor(profile, contested));

        CsvImportResult result = apply(profile, fileFor(profile, contested, mine));

        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().getFirst().line()).isEqualTo(2);   // the contested row, named
        assertThat(result.created()).isEqualTo(1);                     // and the uncontested one still landed
        assertThat(rowsNamed(contested)).as("the index resolved it — one account, not two").isEqualTo(1);
        assertThat(rowsNamed(mine))
                .as("the neighbour's refusal did not take this row with it").isEqualTo(1);
    }

    /**
     * Two real imports at once. The interleaving is not controlled, so this asserts only what holds whichever
     * way it falls: one account exists, and NEITHER call threw. The second half needs a Future — a bare Thread
     * swallows its throwable, which left this passing even if the losing import 500'd.
     */
    @Test
    void twoImportsOfOneUsernameNeverProduceTwoAccounts() throws Exception {
        org = organization();
        UUID profile = tenantProfile(org);
        String username = "grace-" + UUID.randomUUID().toString().substring(0, 8);
        String csv = fileFor(profile, username);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<CsvImportResult> results;
        try {
            List<Future<CsvImportResult>> futures = pool.invokeAll(List.of(
                    () -> apply(profile, csv), () -> apply(profile, csv)));
            results = new ArrayList<>();
            for (Future<CsvImportResult> future : futures) {
                results.add(future.get());   // an escaped exception fails here, which is half the point
            }
        } finally {
            pool.shutdown();
        }

        assertThat(rowsNamed(username)).isEqualTo(1);
        assertThat(results.stream().mapToInt(CsvImportResult::created).sum())
                .as("one wins; the other reports the row rather than throwing").isEqualTo(1);
    }

    /** Runs a whole competing import inside the first creator call — after planning, before writing. */
    private void theOtherAdministratorImports(UUID profile, String csv) {
        AtomicBoolean pending = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (pending.compareAndSet(true, false)) {
                apply(profile, csv);
            }
            return invocation.callRealMethod();
        }).when(creator).create(any(), any());
    }

    private CsvImportResult apply(UUID profile, String csv) {
        asSuperAdmin();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        return orgContext.callInOrg(org, () -> imports.apply(profile, upload(csv)));
    }

    /** A file whose columns come from the profile's own template, so the two cannot disagree. */
    private String fileFor(UUID profile, String... usernames) {
        String header = orgContext.callInOrg(org, () -> templates.templateFor(profile).content())
                .lines().findFirst().orElseThrow();
        StringBuilder csv = new StringBuilder(header).append('\n');
        for (String username : usernames) {
            csv.append(header.replace("username", username)
                    .replace("email", username + "@example.com")
                    .replace("groups", "")).append('\n');
        }
        return csv.toString();
    }

    private int rowsNamed(String username) {
        return ownerJdbc().queryForObject(
                "select count(*) from app_user where org_id = ? and username = ?",
                Integer.class, org, username);
    }

    private UUID organization() {
        String slug = "csv-race-" + UUID.randomUUID().toString().substring(0, 8);
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

    private void asSuperAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(Roles.ADMIN))));
    }
}
