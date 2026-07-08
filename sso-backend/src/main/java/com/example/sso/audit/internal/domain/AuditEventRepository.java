package com.example.sso.audit.internal.domain;

import com.example.sso.audit.AuditCategory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Recent events in one tenant — a tenant admin, or a super-admin drilled into that org. */
    List<AuditEvent> findTop100ByOrgIdOrderByOccurredAtDesc(UUID orgId);

    /** Recent global (org-less) events — the platform tier: an un-drilled super-admin, never all tenants merged. */
    List<AuditEvent> findTop100ByOrgIdIsNullOrderByOccurredAtDesc();

    List<AuditEvent> findTop50ByOrgIdAndPrincipalOrderByOccurredAtDesc(UUID orgId, String principal);

    List<AuditEvent> findTop50ByOrgIdIsNullAndPrincipalOrderByOccurredAtDesc(String principal);

    List<AuditEvent> findTop100ByOrgIdAndCategoryOrderByOccurredAtDesc(UUID orgId, AuditCategory category);

    List<AuditEvent> findTop100ByOrgIdIsNullAndCategoryOrderByOccurredAtDesc(AuditCategory category);

    /** Platform-wide count of events of a type since a moment (e.g. completed sign-ins in the last 30 days). */
    long countByTypeAndOccurredAtAfter(String type, Instant since);

    /** Daily counts of a tenant's sign-in outcomes (SESSION_CREATED / AUTH_FAILURE) since a moment. */
    @Query(value = """
            select cast(date_trunc('day', occurred_at) as date) as day, type as type, count(*) as cnt
            from audit_event
            where org_id = :orgId and type in ('SESSION_CREATED', 'AUTH_FAILURE') and occurred_at >= :since
            group by day, type
            order by day
            """, nativeQuery = true)
    List<DailyCountRow> signInsPerDay(@Param("orgId") UUID orgId, @Param("since") Instant since);

    /** Native projection for {@link #signInsPerDay}: one row per (day, event type). */
    interface DailyCountRow {
        LocalDate getDay();

        String getType();

        long getCnt();
    }
}
