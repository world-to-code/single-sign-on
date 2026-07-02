package com.example.sso.resource.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResourceTypeRepository extends JpaRepository<ResourceType, UUID> {

    /**
     * Loads the type with its member-kind constraints fetch-joined, so a {@link Resource} built around
     * the detached type can still validate attaches ({@code type.allows(...)} reads the LAZY set).
     */
    @Query("select t from ResourceType t left join fetch t.allowedMemberTypes where t.name = :name")
    Optional<ResourceType> findByNameFetchingKinds(@Param("name") String name);
}
