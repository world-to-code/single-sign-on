package com.example.sso.audit.internal.domain;

import com.example.sso.audit.AuditCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findTop100ByOrderByOccurredAtDesc();

    List<AuditEvent> findTop50ByPrincipalOrderByOccurredAtDesc(String principal);

    List<AuditEvent> findTop100ByCategoryOrderByOccurredAtDesc(AuditCategory category);
}
