package com.example.sso.admin.internal.client.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read-only JPA view over the subset of the Spring Authorization Server {@code oauth2_registered_client}
 * table that the admin console needs (listing + launch metadata). The client lifecycle itself is owned
 * by Spring's {@code RegisteredClientRepository} (save/find); this entity only backs list, delete, and
 * the {@code initiate_login_uri} launch column — so it maps just those columns and exposes no setters.
 */
@Entity
@Table(name = "oauth2_registered_client")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class OAuth2RegisteredClientEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 200)
    private String clientName;

    @Column(nullable = false, length = 1000)
    private String scopes;

    @Column(name = "authorization_grant_types", nullable = false, length = 1000)
    private String authorizationGrantTypes;

    @Column(name = "redirect_uris", length = 1000)
    private String redirectUris;

    /** Our launch metadata (OIDC third-party-initiated login URI); added in migration V27. */
    @Column(name = "initiate_login_uri", length = 1024)
    private String initiateLoginUri;
}
