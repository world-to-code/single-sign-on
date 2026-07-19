package com.example.sso.federation.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Links are ALWAYS addressed with their organization first — the same upstream subject may map to a different
 * account in another tenant, so an issuer+subject lookup without the org would resolve across tenants. RLS is
 * the backstop; this signature is the first line.
 */
public interface FederatedIdentityLinkRepository extends JpaRepository<FederatedIdentityLink, UUID> {

    Optional<FederatedIdentityLink> findByOrgIdAndIssuerAndSubject(UUID orgId, String issuer, String subject);

    /** Whether this account already holds an identity at this issuer — the guard that stops a SECOND subject
     *  from claiming it by email (a recycled address must not inherit the previous holder's account). */
    boolean existsByOrgIdAndIssuerAndUserId(UUID orgId, String issuer, UUID userId);

    /**
     * Inserts the link, doing nothing if this identity is already linked. A native upsert rather than a
     * check-then-insert: letting the unique constraint fire inside JPA marks the transaction rollback-only, so
     * catching the violation in Java is NOT enough — the commit then fails anyway and the login dies with it.
     * Deferring the conflict to Postgres keeps the losing side of the race quiet, as it must be (both sides
     * resolved the same account, so the winning row already says what this call wanted to say).
     *
     * Absorbs EITHER unique conflict — the caller reads the resulting state back to tell them apart, since
     * "this identity is already linked here" and "this account already has a different identity here" mean
     * opposite things.
     *
     * @return 1 when the link was created, 0 when a conflict absorbed it
     */
    @Modifying
    @Query(value = """
            insert into federated_identity (org_id, issuer, subject, provider_alias, user_id)
            values (:orgId, :issuer, :subject, :providerAlias, :userId)
            on conflict do nothing""", nativeQuery = true)
    int insertIfAbsent(@Param("orgId") UUID orgId, @Param("issuer") String issuer,
            @Param("subject") String subject, @Param("providerAlias") String providerAlias,
            @Param("userId") UUID userId);

    /** The identities bound to one account within a tenant — the admin listing. */
    List<FederatedIdentityLink> findByOrgIdAndUserIdOrderByCreatedAt(UUID orgId, UUID userId);

    /** One identity, addressed within the acting tenant so an admin cannot reach another org's row. */
    Optional<FederatedIdentityLink> findByIdAndOrgId(UUID id, UUID orgId);

    /** The accounts holding an identity at this upstream — read before retiring them, to revoke their sessions. */
    @Query("select l.userId from FederatedIdentityLink l where l.orgId = :orgId and l.issuer = :issuer")
    List<UUID> findUserIdsAt(@Param("orgId") UUID orgId, @Param("issuer") String issuer);

    /**
     * Drops an org's links for an upstream, e.g. when its provider is deleted or repointed elsewhere. A bulk
     * DELETE rather than the derived form, which would SELECT every row and remove them one by one — and would
     * report nothing, where this returns the count the caller can act on.
     *
     * @return how many identities were retired
     */
    @Modifying
    @Query("delete from FederatedIdentityLink l where l.orgId = :orgId and l.issuer = :issuer")
    int deleteByOrgIdAndIssuer(@Param("orgId") UUID orgId, @Param("issuer") String issuer);
}
