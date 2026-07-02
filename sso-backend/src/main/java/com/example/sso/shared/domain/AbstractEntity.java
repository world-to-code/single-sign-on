package com.example.sso.shared.domain;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import lombok.Getter;

/**
 * Base for entities keyed by a Hibernate-generated UUID primary key. Extracts the identity mapping
 * duplicated across the domain so each entity declares only its own state.
 */
@MappedSuperclass
@Getter
public abstract class AbstractEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
}
