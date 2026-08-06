package com.example.sso.audit.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, Short> {
}
