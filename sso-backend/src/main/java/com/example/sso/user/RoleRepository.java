package com.example.sso.user;

import com.example.sso.shared.IdName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);

    /** (id, name) for the given roles — batch name lookup without loading the EAGER permission graph. */
    @Query("select r.id as id, r.name as name from Role r where r.id in :ids")
    List<IdName> findIdNames(Collection<UUID> ids);
}
