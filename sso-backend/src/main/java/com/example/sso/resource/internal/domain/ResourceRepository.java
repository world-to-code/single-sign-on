package com.example.sso.resource.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {

    /** All resources with their type fetched (the admin list projects the name off each node). */
    @Query("select r from Resource r join fetch r.type order by r.name")
    List<Resource> findAllFetchingType();

    /** One resource with its type fetched (avoids a LAZY hop when projecting a single node). */
    @Query("select r from Resource r join fetch r.type where r.id = :id")
    Optional<Resource> findByIdFetchingType(@Param("id") UUID id);

    /** Advisory-lock key (arbitrary but fixed) serializing resource-edge mutations. */
    String EDGE_MUTATION_LOCK_KEY = "4213370001";

    /**
     * All resources the user holds the given tier over, plus every DAG descendant (recursive walk
     * over {@code resource_edge}; {@code UNION} deduplicates diamonds and stops cycles). The tier is
     * bound — never inline an enum name into the SQL (a rename would silently empty every scope).
     */
    @Query(value = """
            WITH RECURSIVE managed(id) AS (
                SELECT rr.resource_id FROM resource_role rr WHERE rr.user_id = :userId AND rr.tier = :tier
                UNION
                SELECT e.child_id FROM resource_edge e JOIN managed m ON e.parent_id = m.id
            )
            SELECT id FROM managed
            """, nativeQuery = true)
    Set<UUID> findManagedResourceIds(@Param("userId") UUID userId, @Param("tier") String tier);

    /** Whether {@code descendantId} is reachable from {@code ancestorId} (inclusive: a node reaches itself). */
    @Query(value = """
            WITH RECURSIVE down(id) AS (
                SELECT r.id FROM resource r WHERE r.id = :ancestorId
                UNION
                SELECT e.child_id FROM resource_edge e JOIN down d ON e.parent_id = d.id
            )
            SELECT EXISTS (SELECT 1 FROM down WHERE id = :descendantId)
            """, nativeQuery = true)
    boolean reaches(@Param("ancestorId") UUID ancestorId, @Param("descendantId") UUID descendantId);

    /**
     * Serializes graph-edge mutations for the current transaction (released on commit/rollback).
     * The cycle check is check-then-act: without this, two concurrent attaches could each pass the
     * reachability check and then insert the two halves of a cycle. Edge changes are rare admin
     * operations, so one global advisory lock is proportionate.
     *
     * <p><b>REQUIRES an active transaction</b> — {@code pg_advisory_xact_lock} in autocommit is
     * released at statement end, turning the guard into a no-op. Call only from {@code @Transactional}
     * methods (as {@code ResourceGraphService.attachChild} does).
     */
    @Query(value = "SELECT pg_advisory_xact_lock(" + EDGE_MUTATION_LOCK_KEY + ")", nativeQuery = true)
    void lockEdgeMutations();

    /** Leaf-member ids of the given kind across the given resources (callers guard the empty set). */
    @Query(value = """
            SELECT DISTINCT rm.member_id FROM resource_member rm
            WHERE rm.resource_id IN (:resourceIds) AND rm.member_type = :memberType
            """, nativeQuery = true)
    Set<String> findMemberIds(@Param("resourceIds") Collection<UUID> resourceIds,
                              @Param("memberType") String memberType);

    /** Whether any resource is of the given type (guards type deletion). */
    @Query("select count(r) > 0 from Resource r where r.type.id = :typeId")
    boolean existsByTypeId(@Param("typeId") UUID typeId);

    /** Drops every membership of the given kind/id across all resources (its target was deleted elsewhere). */
    @Modifying
    @Query(value = "DELETE FROM resource_member WHERE member_type = :memberType AND member_id = :memberId",
            nativeQuery = true)
    void deleteMembersByTypeAndId(@Param("memberType") String memberType, @Param("memberId") String memberId);
}
