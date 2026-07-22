package com.example.sso.mapping;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * For each of these attribute keys, the PRIVILEGE-granting targets that rules reading it confer.
     *
     * <p>Whoever writes such a key chooses who satisfies those rules, so this is the reach a caller has to
     * bound before letting them write it. {@code RESOURCE_MEMBER} is excluded for the same reason the
     * evaluator excludes it: membership of a resource confers no authority.
     *
     * <p>Keys nothing reads are absent from the result rather than mapped to an empty set.
     */
    Map<String, Set<MappingTarget>> privilegeTargetsByKey(Collection<String> attrKeys);

    /**
     * The same question for REMOVAL: the privilege-granting targets of rules that confer on the ABSENCE of
     * these keys — those reading them through a negative operator ({@code NOT_EXISTS}, {@code NOT_EQUALS}).
     *
     * <p>Removal is asked separately because most of it de-escalates: taking away the value that granted a
     * role retracts it, and an administrator must always be able to do that even when they could not have
     * granted it. Only a negative operator turns a removal into a grant.
     */
    Map<String, Set<MappingTarget>> privilegeTargetsGrantedByAbsence(Collection<String> attrKeys);
}
