package com.example.sso.admin.internal.client.domain;

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

    /** Persists our launch-metadata column on the client row Spring just saved. */
    @Modifying
    @Query("update OAuth2RegisteredClientEntity c set c.initiateLoginUri = :uri where c.clientId = :clientId")
    void updateInitiateLoginUri(@Param("clientId") String clientId, @Param("uri") String uri);
}
