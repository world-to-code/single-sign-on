package com.example.sso.audit.internal.domain;

import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditSubjectType;
import java.time.Instant;
import java.util.Collection;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Recent events in one tenant — a tenant admin, or a super-admin drilled into that org. */
    List<AuditEvent> findTop100ByOrgIdOrderByOccurredAtDesc(UUID orgId);

    /** Recent global (org-less) events — the platform tier: an un-drilled super-admin, never all tenants merged. */
    List<AuditEvent> findTop100ByOrgIdIsNullOrderByOccurredAtDesc();

    /**
     * Everything about one person: what they DID (they are the actor) and what was done TO them (they are the
     * subject). Two different facts that a single field cannot hold — which is why the audit row keeps the
     * actor and the subject apart, and why this query has to ask for both.
     */
    @Query("""
            select e from AuditEvent e
             where e.orgId = :orgId
               and (e.principal = :username
                    or (e.subjectType = :subjectType and e.subjectId = :userId))
             order by e.occurredAt desc
            """)
    List<AuditEvent> findRecentAbout(UUID orgId, String username, AuditSubjectType subjectType,
            String userId, Limit limit);

    /** The same, for a GLOBAL/platform account (no owning organization). */
    @Query("""
            select e from AuditEvent e
             where e.orgId is null
               and (e.principal = :username
                    or (e.subjectType = :subjectType and e.subjectId = :userId))
             order by e.occurredAt desc
            """)
    List<AuditEvent> findRecentAboutGlobal(String username, AuditSubjectType subjectType,
            String userId, Limit limit);


    List<AuditEvent> findTop100ByOrgIdAndCategoryOrderByOccurredAtDesc(UUID orgId, AuditCategory category);

    List<AuditEvent> findTop100ByOrgIdIsNullAndCategoryOrderByOccurredAtDesc(AuditCategory category);

    /** Recent events in one tenant restricted to a set of categories — the ALL view for a category-scoped reader. */
    List<AuditEvent> findTop100ByOrgIdAndCategoryInOrderByOccurredAtDesc(UUID orgId, Collection<AuditCategory> cats);

    List<AuditEvent> findTop100ByOrgIdIsNullAndCategoryInOrderByOccurredAtDesc(Collection<AuditCategory> cats);

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

    /** The next batch to seal. Ordered by id, the only column that reflects the order rows became visible. */
    @Query("select e from AuditEvent e where e.seq is null order by e.id")
    List<AuditEvent> findUnsealedOldestFirst(Limit limit);

    @Query("select max(e.seq) from AuditEvent e")
    Optional<Long> highestSealedSeq();

    @Query("select e from AuditEvent e where e.seq is not null order by e.seq")
    List<AuditEvent> findSealedInChainOrder();
}
