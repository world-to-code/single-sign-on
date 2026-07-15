package com.example.sso.mapping;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Manages metadata-driven mapping rules (predicate → group membership) in the acting tier. Creating or editing a
 * rule re-evaluates it across the org; deleting it retracts the memberships it materialized. The authorization
 * that the actor may grant the target group's membership is enforced by the admin controller before these are
 * called; the implementation additionally validates the group exists in the acting tier and is not a system group.
 */
public interface MappingRuleService {

    /** Creates a rule and materializes it (adds every currently-matching user to the group). */
    MappingRuleView create(MappingRuleSpec spec);

    /** Updates a rule's predicate/target and re-materializes (adds newly-matching, retracts no-longer-matching). */
    MappingRuleView update(UUID id, MappingRuleSpec spec);

    /** Deletes a rule, retracting every membership it materialized. */
    void delete(UUID id);

    /** Every rule in the acting tier. */
    List<MappingRuleView> list();

    MappingRuleView get(UUID id);

    /** Dry run: the ids of the users the given predicate currently matches in the acting tier. */
    Set<UUID> preview(MappingRuleSpec spec);
}
