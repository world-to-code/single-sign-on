package com.example.sso.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findByPrincipalOrderByOccurredAtDesc(String principal, Pageable pageable);

    List<AuditEvent> findTop100ByOrderByOccurredAtDesc();
}
