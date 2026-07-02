package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.internal.domain.AuditEvent;
import com.example.sso.audit.internal.domain.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Default {@link AuditService}. Writes run in their own transaction so an audit write never rolls
 * back (or is rolled back by) the surrounding business transaction. The recent-events read maps the
 * internal {@link AuditEvent} entity to the public {@link AuditEntry} projection.
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {
    private final AuditEventRepository repository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditRecord record) {
        repository.save(new AuditEvent(record.type(), record.principal(), record.success(),
                record.detail(), record.remoteIp()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditType type, String principal, boolean success) {
        record(new AuditRecord(type, principal, success, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> recent() {
        return repository.findTop100ByOrderByOccurredAtDesc().stream().map(this::toEntry).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> recentForPrincipal(String principal) {
        return repository.findTop50ByPrincipalOrderByOccurredAtDesc(principal).stream()
                .map(this::toEntry).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> recentByCategory(AuditCategory category) {
        return repository.findTop100ByCategoryOrderByOccurredAtDesc(category).stream()
                .map(this::toEntry).toList();
    }

    private AuditEntry toEntry(AuditEvent event) {
        return new AuditEntry(event.getId(), event.getOccurredAt(), event.getPrincipal(),
                event.getType(), event.getCategory(), event.isSuccess(), event.getDetail());
    }
}
