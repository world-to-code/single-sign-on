package com.example.sso.saml;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SamlRelyingPartyRepository extends JpaRepository<SamlRelyingParty, UUID> {

    Optional<SamlRelyingParty> findByEntityId(String entityId);

    boolean existsByEntityId(String entityId);
}
