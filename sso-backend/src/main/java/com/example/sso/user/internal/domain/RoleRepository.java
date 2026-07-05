package com.example.sso.user.internal.domain;

import com.example.sso.shared.IdName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    /**
     * The GLOBAL/system role with this name (org_id IS NULL). Role names are unique only per tier now, so
     * name-based lookup/assignment always resolves to the global tier — tenant (org) roles are addressed by
     * id. Deterministic via the {@code uq_role_name_global} partial unique index (≤1 row).
     */
    Optional<Role> findByNameAndOrgIdIsNull(String name);

    /** A role with this name within the given org — the per-tenant uniqueness check on creation. */
    Optional<Role> findByNameAndOrgId(String name, UUID orgId);

    /** (id, name) for the given roles — batch name lookup without loading the EAGER permission graph. */
    @Query("select r.id as id, r.name as name from Role r where r.id in :ids")
    List<IdName> findIdNames(Collection<UUID> ids);
}
