package com.example.sso.audit.internal.application;

import com.example.sso.audit.internal.domain.AuditEvent;
import com.example.sso.audit.internal.domain.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a fully-formed audit event in its OWN transaction, so an audit write is never rolled back by
 * (or rolls back) the surrounding business transaction. Enrichment (the actor lookup, request capture)
 * runs in {@code AuditServiceImpl.record} BEFORE this boundary opens — a failing enrichment query can then
 * never poison this write's connection. A separate bean (not a self-invoked method) so the REQUIRES_NEW
 * proxy actually applies.
 */
@Component
@RequiredArgsConstructor
class AuditEventWriter {

    private final AuditEventRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void save(AuditEvent event) {
        repository.save(event);
    }
}
