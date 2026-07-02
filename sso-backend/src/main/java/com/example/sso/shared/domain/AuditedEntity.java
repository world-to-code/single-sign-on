package com.example.sso.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/** UUID-keyed entity that also records its immutable creation timestamp ({@code created_at}). */
@MappedSuperclass
@Getter
public abstract class AuditedEntity extends AbstractEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
