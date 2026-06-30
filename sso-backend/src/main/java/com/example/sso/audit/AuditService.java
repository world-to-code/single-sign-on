package com.example.sso.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records audit events. Writes run in their own transaction so that an audit write never
 * rolls back (or is rolled back by) the surrounding business transaction.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String type, String principal, boolean success, String detail, String remoteIp) {
        repository.save(new AuditEvent(type, principal, success, detail, remoteIp));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String type, String principal, boolean success) {
        record(type, principal, success, null, null);
    }
}
