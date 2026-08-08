package com.example.sso.user.internal.application;

import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which rung of the specificity ladder decided, not merely what it decided.
 *
 * <p>The ladder already computes this and throws it away on the next line, so "why was Ada refused
 * {@code user:delete}?" has no answer even though the answer existed a microsecond earlier. Recovering it is
 * what lets a denial explain itself to an investigator instead of being a bare false.
 *
 * <p><b>Why the reason needs its own tests rather than riding along with the outcome ones.</b> Several rungs
 * produce the SAME outcome from different causes — a permission absent because a role deny cut it and one
 * absent because nobody ever granted it are both "not held". A test that asserts only the boolean passes
 * whether or not the ladder is in the right order, which is precisely the mutation this file exists to catch.
 *
 * <p>The reason must come from the branch that produced the outcome — never from a second pass that infers
 * "probably the role tier" from the result. That would be a plausible answer rather than a true one, and an
 * investigator cannot tell the difference.
 */
class DenyResolverReasonTest {

    private static final String PERMISSION = Permissions.USER_UPDATE;

    private final DenyResolver resolver = new DenyResolver();

    private PermissionVerdict verdict(DenyInputs in) {
        return resolver.verdicts(in).get(PERMISSION);
    }

    private DenyInputs inputs(Set<String> userAllow, Set<String> roleAllow, Set<String> roleNames,
                              Set<String> userDeny, Set<String> roleDeny, Set<String> groupDeny,
                              Set<String> orgDeny, Set<String> platformDeny) {
        return new DenyInputs(userAllow, roleAllow, roleNames,
                new DenyRows(userDeny, roleDeny, groupDeny, orgDeny, platformDeny));
    }

    private DenyInputs nothing() {
        return inputs(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    void aPermissionNobodyMentionsHasNoDecidingLevel() {
        PermissionVerdict verdict = verdict(nothing());

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.SILENT);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.NO_LEVEL_SPOKE);
    }

    @Test
    void aUserGrantIsAllowedAtTheUserLevel() {
        PermissionVerdict verdict = verdict(inputs(Set.of(PERMISSION), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of()));

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.ALLOW);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.ALLOWED_AT_USER_LEVEL);
    }

    @Test
    void aRoleGrantIsAllowedAtTheRoleLevel() {
        PermissionVerdict verdict = verdict(inputs(Set.of(), Set.of(PERMISSION), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of()));

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.ALLOW);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.ALLOWED_AT_ROLE_LEVEL);
    }

    @Test
    void aUserDenyIsTheMostSpecificRefusal() {
        PermissionVerdict verdict = verdict(inputs(Set.of(PERMISSION), Set.of(PERMISSION), Set.of(),
                Set.of(PERMISSION), Set.of(), Set.of(), Set.of(), Set.of()));

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.DENY);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.DENIED_AT_USER_LEVEL);
    }

    /**
     * The pair that makes the ladder's ORDER observable. Both are "held", and they are held for opposite
     * reasons — a user grant out-ranking a role deny, versus a plain role grant nobody objected to. Swap two
     * rungs and the outcomes stay identical while these two reasons change places.
     */
    @Test
    void aUserGrantOutranksARoleDenyAndSaysSo() {
        PermissionVerdict verdict = verdict(inputs(Set.of(PERMISSION), Set.of(), Set.of(),
                Set.of(), Set.of(PERMISSION), Set.of(), Set.of(), Set.of()));

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.ALLOW);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.ALLOWED_AT_USER_LEVEL);
    }

    @Test
    void aRoleDenyRefusesARoleGrant() {
        PermissionVerdict verdict = verdict(inputs(Set.of(), Set.of(PERMISSION), Set.of(),
                Set.of(), Set.of(PERMISSION), Set.of(), Set.of(), Set.of()));

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.DENY);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.DENIED_AT_ROLE_LEVEL);
    }

    @Test
    void aGroupDenyRefusesAtItsOwnLevelNotTheRoleOne() {
        PermissionVerdict verdict = verdict(inputs(Set.of(), Set.of(PERMISSION), Set.of(),
                Set.of(), Set.of(), Set.of(PERMISSION), Set.of(), Set.of()));

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.DENY);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.DENIED_AT_GROUP_LEVEL);
    }

    /** There is no org-level ALLOW, so an org deny only ever speaks when nothing above it did. */
    @Test
    void anOrgDenyRefusesOnlyWhatNoGrantClaimed() {
        PermissionVerdict verdict = verdict(inputs(Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(PERMISSION), Set.of()));

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.DENY);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.DENIED_AT_ORG_LEVEL);
    }

    @Test
    void aRoleGrantOutranksAnOrgDeny() {
        PermissionVerdict verdict = verdict(inputs(Set.of(), Set.of(PERMISSION), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(PERMISSION), Set.of()));

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.ALLOW);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.ALLOWED_AT_ROLE_LEVEL);
    }

    /**
     * The platform veto beats even a USER allow, and it is applied after the ladder rather than inside it —
     * so it needs its own reason or the row would claim the user grant won.
     */
    @Test
    void thePlatformVetoOverridesEvenAUserGrant() {
        DenyInputs in = inputs(Set.of(PERMISSION), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(PERMISSION));

        assertThat(resolver.verdicts(in).get(PERMISSION).reason()).isEqualTo(DecisionReason.PLATFORM_VETO);
        assertThat(resolver.effectiveAuthorities(in)).doesNotContain(PERMISSION);
    }

    /** A super-admin is exempt from every deny, which is a reason in its own right, not an absent one. */
    @Test
    void aSuperAdminIsExemptAndTheVerdictSaysWhy() {
        PermissionVerdict verdict = verdict(inputs(Set.of(PERMISSION), Set.of(), Set.of(Roles.ADMIN),
                Set.of(PERMISSION), Set.of(), Set.of(), Set.of(), Set.of(PERMISSION)));

        assertThat(verdict.decision()).isEqualTo(PermissionDecision.ALLOW);
        assertThat(verdict.reason()).isEqualTo(DecisionReason.SUPER_ADMIN_EXEMPT);
    }

    /**
     * The fold must not drift from the verdicts it folds. This is the contract that keeps the reason honest:
     * if the two ever disagree, the explanation is describing a decision the system did not make.
     */
    @Test
    void theAuthoritySetAgreesWithTheVerdictsItWasFoldedFrom() {
        DenyInputs in = inputs(Set.of(Permissions.USER_UPDATE), Set.of(Permissions.GROUP_READ), Set.of(),
                Set.of(), Set.of(Permissions.GROUP_READ), Set.of(), Set.of(), Set.of());

        Set<String> effective = resolver.effectiveAuthorities(in);

        resolver.verdicts(in).forEach((permission, verdict) -> {
            if (verdict.decision() == PermissionDecision.ALLOW) {
                assertThat(effective).as("allowed %s must be effective", permission).contains(permission);
            }
            if (verdict.decision() == PermissionDecision.DENY) {
                assertThat(effective).as("denied %s must not be effective", permission)
                        .doesNotContain(permission);
            }
        });
    }
}
