package com.example.sso.portal.internal.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyBindingConditionRepository extends JpaRepository<PolicyBindingCondition, UUID> {

    /** Every condition of the given bindings, in ONE query (the resolver's candidate set — no N+1). RLS-scoped. */
    List<PolicyBindingCondition> findByBindingIdIn(Collection<UUID> bindingIds);

    /** The conditions of one binding (the writer builds its current group to diff against the wanted one). */
    List<PolicyBindingCondition> findByBindingId(UUID bindingId);
}
