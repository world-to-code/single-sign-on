package com.example.sso.directory.internal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectorySyncRunRepository extends JpaRepository<DirectorySyncRun, UUID> {

    List<DirectorySyncRun> findByConnectorIdOrderByStartedAtDesc(UUID connectorId, Pageable pageable);
}
