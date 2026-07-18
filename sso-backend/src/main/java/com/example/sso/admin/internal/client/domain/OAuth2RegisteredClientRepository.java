package com.example.sso.admin.internal.client.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Admin-side read/delete access to the Spring Authorization Server {@code oauth2_registered_client}
 * table. Registration (save) still goes through Spring's {@code RegisteredClientRepository}; this only
 * covers list, delete, and the launch-metadata ({@code initiate_login_uri}) update.
 */
public interface OAuth2RegisteredClientRepository extends JpaRepository<OAuth2RegisteredClientEntity, String> {

    /** Global/platform clients (org_id IS NULL) — what the platform admin manages with no org bound. */
    List<OAuth2RegisteredClientEntity> findAllByOrgIdIsNull();

    /** Clients owned by one tenant — what a tenant admin (or a super-admin drilled into that org) manages. */
    List<OAuth2RegisteredClientEntity> findAllByOrgId(UUID orgId);

    /** A client by its OAuth {@code client_id} — used to surface the host-agnostic first-party admin console
     *  in every tier's launchable-app catalog (its {@code id} is a random UUID, so a client_id lookup is needed). */
    Optional<OAuth2RegisteredClientEntity> findByClientId(String clientId);

    /** Persists our launch-metadata column on the client row Spring just saved, keyed by its globally-unique
     *  internal id — NOT client_id, which is unique only per tenant, so a client_id-keyed write would overwrite
     *  every tenant's (and the global) row sharing that client_id. */
    @Modifying
    @Query("update OAuth2RegisteredClientEntity c set c.initiateLoginUri = :uri where c.id = :id")
    void updateInitiateLoginUriById(@Param("id") String id, @Param("uri") String uri);
}
