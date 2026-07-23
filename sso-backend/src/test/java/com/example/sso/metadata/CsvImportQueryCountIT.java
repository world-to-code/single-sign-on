package com.example.sso.metadata;

import com.example.sso.metadata.internal.application.CsvImportService;
import com.example.sso.metadata.internal.application.CsvTemplateService;
import com.example.sso.metadata.internal.application.CsvImportResult;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.role.Roles;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What a row actually costs, measured rather than traced.
 *
 * <p>A review put the per-row cost at "roughly thirty round trips" from reading the call chain and said
 * plainly that it had not instrumented anything. That is the right way to raise it and the wrong way to size a
 * fix: the file-invariant lookups it names are real, but so is the group memo that already removes one of
 * them, and only counting tells you which of the two dominates.
 *
 * <p>So this measures, and then holds the number. A change that adds a per-row query to the import fails here
 * with both numbers in the message.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class CsvImportQueryCountIT extends AbstractIntegrationTest {

    /** Enough rows that the per-row cost dominates the once-per-file work. */
    private static final int ROWS = 10;

    /**
     * Measured at 18.9 per row when this was written (189 statements for ten rows). The ceiling sits just above
     * that — real (non-integer) division, so a regression adding roughly one query on every row trips it. It
     * catches the cost GROWING, it is not a target to tune against.
     *
     * <p>The earlier form divided as integers ({@code 189 / 10 == 18}) against a ceiling of 25, so it took
     * about eight extra queries PER ROW to trip — the docstring's "just above" was fiction. Real division and a
     * ceiling one query-per-row above the measurement fix both.
     *
     * <p>Worth recording what the measurement settled: a review put this at "roughly thirty round trips" from
     * reading the call chain, and said plainly it had not instrumented anything. The traced figure was high by
     * about a third, because the group memo already removes one of the per-row lookups it counted.
     */
    private static final double MAX_QUERIES_PER_ROW = 20.0;

    @Autowired CsvImportService imports;
    @Autowired CsvTemplateService templates;
    @Autowired ProfileService profiles;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;
    @Autowired EntityManagerFactory entityManagerFactory;

    private UUID org;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (org != null) {
            ownerJdbc().update("delete from app_user where org_id = ?", org);
            organizations.delete(org);
        }
    }

    @Test
    void aRowCostsNoMoreQueriesThanItsCeiling() {
        org = organization();
        UUID profile = tenantProfile(org);

        Statistics statistics = statistics();
        statistics.clear();
        CsvImportResult result = asSuperAdmin(() -> orgContext.callInOrg(org,
                () -> imports.apply(profile, upload(fileOf(profile, ROWS)))));
        long queries = statistics.getPrepareStatementCount();

        assertThat(result.created()).isEqualTo(ROWS);
        assertThat((double) queries / ROWS)
                .as("%d statements for %d rows — the per-row cost has grown past its ceiling", queries, ROWS)
                .isLessThanOrEqualTo(MAX_QUERIES_PER_ROW);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** A file of distinct rows, with columns from the profile's own template. */
    private String fileOf(UUID profile, int rows) {
        String header = orgContext.callInOrg(org, () -> templates.templateFor(profile).content())
                .lines().findFirst().orElseThrow();
        StringBuilder csv = new StringBuilder(header).append('\n');
        for (int i = 0; i < rows; i++) {
            String username = "cost-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
            csv.append(header.replace("username", username)
                    .replace("email", username + "@example.com")
                    .replace("groups", "")).append('\n');
        }
        return csv.toString();
    }

    private <T> T asSuperAdmin(Supplier<T> body) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(Roles.ADMIN))));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        return body.get();
    }

    private UUID organization() {
        String slug = "cost-" + UUID.randomUUID().toString().substring(0, 8);
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
}
