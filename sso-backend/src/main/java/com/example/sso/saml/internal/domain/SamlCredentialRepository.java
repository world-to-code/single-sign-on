package com.example.sso.saml.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SamlCredentialRepository extends JpaRepository<SamlCredential, UUID> {

    /** The newest active SAML credential owned by {@code orgId}, if the tenant has its own. */
    Optional<SamlCredential> findFirstByActiveTrueAndOrgIdOrderByCreatedAtDesc(UUID orgId);
}
