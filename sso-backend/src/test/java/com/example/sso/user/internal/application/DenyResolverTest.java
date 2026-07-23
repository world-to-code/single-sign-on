package com.example.sso.user.internal.application;

import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deny-resolution truth table. Negative permissions are the subtlest change in authorization, so this
 * asserts BOTH directions symmetrically — that a deny withholds what it must, and that it does NOT withhold
 * what a more specific allow (or an apex carve-out, or super-exemption) protects.
 */
class DenyResolverTest {

    private final DenyResolver resolver = new DenyResolver();

    /** A fluent builder over the nine deny/allow sets — every field defaults to empty. */
    private static final class In {
        private Set<String> userAllow = Set.of();
        private Set<String> roleAllow = Set.of();
        private Set<String> roleNames = Set.of();
        private Set<String> userDeny = Set.of();
        private Set<String> roleDeny = Set.of();
        private Set<String> groupDeny = Set.of();
        private Set<String> orgDeny = Set.of();
        private Set<String> platformDeny = Set.of();

        private In userAllow(String... p) { this.userAllow = Set.of(p); return this; }
        private In roleAllow(String... p) { this.roleAllow = Set.of(p); return this; }
        private In roleNames(String... p) { this.roleNames = Set.of(p); return this; }
        private In userDeny(String... p) { this.userDeny = Set.of(p); return this; }
        private In roleDeny(String... p) { this.roleDeny = Set.of(p); return this; }
        private In groupDeny(String... p) { this.groupDeny = Set.of(p); return this; }
        private In orgDeny(String... p) { this.orgDeny = Set.of(p); return this; }
        private In platformDeny(String... p) { this.platformDeny = Set.of(p); return this; }

        private DenyInputs build() {
            return new DenyInputs(userAllow, roleAllow, roleNames,
                    new DenyRows(userDeny, roleDeny, groupDeny, orgDeny, platformDeny));
        }
    }

    private Set<String> resolve(In in) {
        return resolver.effectiveAuthorities(in.build());
    }

    // --- baseline ---

    @Test
    void withNoDeniesTheGrantExpandsNormally() {
        Set<String> effective = resolve(new In().roleAllow(Permissions.USER_UPDATE));

        assertThat(effective).contains(Permissions.USER_UPDATE, Permissions.USER_READ); // update implies read
    }

    @Test
    void aUserDenyRemovesAGrantButLeavesTheRest() {
        Set<String> effective = resolve(new In()
                .roleAllow(Permissions.USER_READ, Permissions.USER_UPDATE)
                .userDeny(Permissions.USER_READ));

        assertThat(effective).contains(Permissions.USER_UPDATE).doesNotContain(Permissions.USER_READ);
    }

    // --- specificity: a more specific ALLOW beats a less specific DENY ---

    @Test
    void aUserAllowBeatsAnOrgDeny() {
        Set<String> effective = resolve(new In()
                .userAllow(Permissions.USER_READ)
                .orgDeny(Permissions.USER_READ));

        assertThat(effective).contains(Permissions.USER_READ);
    }

    @Test
    void aUserAllowBeatsARoleDeny() {
        Set<String> effective = resolve(new In()
                .userAllow(Permissions.USER_READ)
                .roleDeny(Permissions.USER_READ));

        assertThat(effective).contains(Permissions.USER_READ);
    }

    @Test
    void sameLevelDenyBeatsAllow() {
        Set<String> effective = resolve(new In()
                .roleAllow(Permissions.USER_READ)
                .roleDeny(Permissions.USER_READ)); // no apex grant → the role deny stands

        assertThat(effective).doesNotContain(Permissions.USER_READ);
    }

    // --- the ROLE/GROUP level (the apex carve-out that DECIDES which role denies reach here is applied
    //     upstream in EffectiveAuthorityResolver — read denies for apex roles only — and is covered end-to-end
    //     in PermissionDenyResolutionIT; here roleDeny/groupDeny are the already-carved inputs) ---

    @Test
    void aGroupDenyRemovesAGrant() {
        Set<String> effective = resolve(new In()
                .roleAllow(Permissions.USER_READ)
                .groupDeny(Permissions.USER_READ)); // a GROUP-subject deny beats the role allow at its level

        assertThat(effective).doesNotContain(Permissions.USER_READ);
    }

    // --- org deny bites an IMPLIED read (its only reachable target, since grants are more specific) ---

    @Test
    void anOrgDenyRemovesAnImpliedReadWhileKeepingTheMutatingGrant() {
        Set<String> effective = resolve(new In()
                .roleAllow(Permissions.USER_UPDATE)  // implies user:read
                .orgDeny(Permissions.USER_READ));

        assertThat(effective).contains(Permissions.USER_UPDATE).doesNotContain(Permissions.USER_READ);
    }

    // --- platform veto is absolute ---

    @Test
    void aPlatformVetoBeatsEvenAUserAllow() {
        Set<String> effective = resolve(new In()
                .userAllow(Permissions.USER_READ)
                .platformDeny(Permissions.USER_READ));

        assertThat(effective).doesNotContain(Permissions.USER_READ);
    }

    // --- implication runs BEFORE subtraction, so a macro cannot resurrect a denied finer scope ---

    @Test
    void theAuditMacroDoesNotResurrectADeniedFinerScope() {
        Set<String> effective = resolve(new In()
                .roleAllow(Permissions.AUDIT_READ)          // macro-expands to every category + PII
                .userDeny(Permissions.AUDIT_READ_PII));     // but PII is denied

        assertThat(effective)
                .contains(Permissions.AUDIT_READ, Permissions.AUDIT_READ_SESSION)
                .doesNotContain(Permissions.AUDIT_READ_PII);
    }

    // --- wildcard expansion on both sides ---

    @Test
    void aWildcardDenyRemovesEveryMemberOfTheResource() {
        Set<String> effective = resolve(new In()
                .roleAllow(Permissions.USER_READ, Permissions.USER_CREATE,
                        Permissions.USER_UPDATE, Permissions.USER_DELETE)
                .userDeny("user:*"));

        assertThat(effective).doesNotContain(Permissions.USER_READ, Permissions.USER_CREATE,
                Permissions.USER_UPDATE, Permissions.USER_DELETE);
    }

    // --- the wildcard TOKEN survives only when every member does (grant-ceiling correctness) ---

    @Test
    void aWildcardTokenSurvivesWhenNoMemberIsDenied() {
        Set<String> effective = resolve(new In().roleAllow("user:*"));

        assertThat(effective).contains("user:*",
                Permissions.USER_READ, Permissions.USER_CREATE, Permissions.USER_UPDATE, Permissions.USER_DELETE);
    }

    @Test
    void aWildcardTokenIsDroppedWhenAnyMemberIsDenied() {
        Set<String> effective = resolve(new In()
                .roleAllow("user:*")
                .userDeny(Permissions.USER_DELETE));

        // The surviving actions remain, but the token is gone: you may not hand out user:* without user:delete.
        assertThat(effective)
                .contains(Permissions.USER_READ, Permissions.USER_CREATE, Permissions.USER_UPDATE)
                .doesNotContain("user:*", Permissions.USER_DELETE);
    }

    // --- super is exempt from every deny ---

    @Test
    void roleAdminIsExemptFromEveryDenyIncludingThePlatformVeto() {
        Set<String> effective = resolve(new In()
                .roleNames(Roles.ADMIN)
                .roleAllow("*:*")
                .platformDeny(Permissions.USER_READ)   // ignored for the super
                .userDeny(Permissions.ORG_CREATE));    // ignored

        assertThat(effective).contains(Roles.ADMIN, Permissions.USER_READ, Permissions.ORG_CREATE, "*:*")
                .containsAll(Permissions.ALL);
    }

    // --- role names / non-permission authorities are never touched by deny ---

    @Test
    void roleNamesAreCarriedThroughUntouchedByDeny() {
        Set<String> effective = resolve(new In()
                .roleNames(Roles.USER, Roles.GROUP_ADMIN)
                .roleAllow(Permissions.USER_READ)
                .userDeny(Permissions.USER_READ)); // denies the permission, never the role name

        assertThat(effective).contains(Roles.USER, Roles.GROUP_ADMIN).doesNotContain(Permissions.USER_READ);
    }

    // --- the SILENT tier: an ungranted, undenied permission is never conferred (guards default-allow) ---

    @Test
    void anUngrantedUndeniedPermissionIsNeverEffective() {
        // EXACTLY, not contains: the failure that matters here is the set being too LARGE — a default-allow
        // inversion (SILENT→ALLOW, or allowed=ALL) would confer the whole catalog and a `contains` is blind to it.
        Set<String> effective = resolve(new In().roleAllow(Permissions.USER_UPDATE));

        assertThat(effective).containsExactlyInAnyOrder(Permissions.USER_UPDATE, Permissions.USER_READ);
    }

    @Test
    void anEmptyGrantResolvesToNothing() {
        assertThat(resolve(new In())).isEmpty();
    }

    // --- specificity ladder ROLE > ORG on the SAME permission (a role allow out-ranks an org deny) ---

    @Test
    void aRoleAllowBeatsAnOrgDenyOfTheSamePermission() {
        Set<String> effective = resolve(new In()
                .roleAllow(Permissions.USER_READ)
                .orgDeny(Permissions.USER_READ)); // ORG is less specific than ROLE → the allow wins

        assertThat(effective).contains(Permissions.USER_READ);
    }

    // --- exemption is the platform super ONLY: a tenant ROLE_ORG_ADMIN is still subject to deny ---

    @Test
    void aTenantOrgAdminIsNotExemptFromDeny() {
        // Exemption keys strictly on Roles.ADMIN. If it broadened to ORG_ADMIN a tenant admin would bypass the
        // platform veto — an escalation. The org admin keeps its role NAME but loses the vetoed permission.
        Set<String> effective = resolve(new In()
                .roleNames(Roles.ORG_ADMIN)
                .roleAllow(Permissions.USER_READ)
                .platformDeny(Permissions.USER_READ));

        assertThat(effective).contains(Roles.ORG_ADMIN).doesNotContain(Permissions.USER_READ);
    }

    // --- the platform veto also drops a wildcard TOKEN whose member it removed (post-subtraction recompute) ---

    @Test
    void aPlatformVetoOfAMemberDropsTheWildcardToken() {
        Set<String> effective = resolve(new In()
                .roleAllow("user:*")
                .platformDeny(Permissions.USER_DELETE));

        assertThat(effective)
                .contains(Permissions.USER_READ, Permissions.USER_CREATE, Permissions.USER_UPDATE)
                .doesNotContain("user:*", Permissions.USER_DELETE);
    }
}
