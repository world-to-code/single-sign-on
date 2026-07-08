package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSignInDay;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.internal.domain.AuditEvent;
import com.example.sso.audit.internal.domain.AuditEventRepository;
import com.example.sso.tenancy.OrgContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Default {@link AuditService}. Writes run in their own transaction so an audit write never rolls
 * back (or is rolled back by) the surrounding business transaction. The recent-events read maps the
 * internal {@link AuditEvent} entity to the public {@link AuditEntry} projection.
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {
    private final AuditEventRepository repository;
    private final OrgContext orgContext;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditRecord record) {
        // Tag the tenant: the caller's explicit org (the login flow, which knows it before the context is
        // bound) wins; otherwise default to the request's bound tenant context (admin/post-login actions).
        UUID orgId = record.orgId() != null ? record.orgId() : orgContext.currentOrg().orElse(null);
        repository.save(new AuditEvent(record.type(), record.principal(), record.success(),
                record.detail(), record.remoteIp(), record.subjectType(), record.subjectId(), orgId));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditType type, String principal, boolean success) {
        record(new AuditRecord(type, principal, success, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> recent(UUID orgId) {
        List<AuditEvent> events = orgId == null
                ? repository.findTop100ByOrgIdIsNullOrderByOccurredAtDesc()
                : repository.findTop100ByOrgIdOrderByOccurredAtDesc(orgId);
        return events.stream().map(this::toEntry).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> recentForPrincipal(UUID orgId, String principal) {
        List<AuditEvent> events = orgId == null
                ? repository.findTop50ByOrgIdIsNullAndPrincipalOrderByOccurredAtDesc(principal)
                : repository.findTop50ByOrgIdAndPrincipalOrderByOccurredAtDesc(orgId, principal);
        return events.stream().map(this::toEntry).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> recentByCategory(UUID orgId, AuditCategory category) {
        List<AuditEvent> events = orgId == null
                ? repository.findTop100ByOrgIdIsNullAndCategoryOrderByOccurredAtDesc(category)
                : repository.findTop100ByOrgIdAndCategoryOrderByOccurredAtDesc(orgId, category);
        return events.stream().map(this::toEntry).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long signInsSince(Instant since) {
        return repository.countByTypeAndOccurredAtAfter(AuditType.SESSION_CREATED.name(), since);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditSignInDay> signInTrend(UUID orgId, Instant since) {
        Map<LocalDate, long[]> byDay = new TreeMap<>(); // day -> [successes, failures]
        for (AuditEventRepository.DailyCountRow row : repository.signInsPerDay(orgId, since)) {
            long[] outcome = byDay.computeIfAbsent(row.getDay(), d -> new long[2]);
            if (AuditType.SESSION_CREATED.name().equals(row.getType())) {
                outcome[0] += row.getCnt();
            } else {
                outcome[1] += row.getCnt();
            }
        }
        return byDay.entrySet().stream()
                .map(e -> new AuditSignInDay(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    private AuditEntry toEntry(AuditEvent event) {
        return new AuditEntry(event.getId(), event.getOccurredAt(), event.getPrincipal(),
                event.getType(), event.getCategory(), event.isSuccess(), event.getDetail(),
                event.getSubjectType(), event.getSubjectId());
    }
}
