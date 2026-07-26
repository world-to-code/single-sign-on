package com.example.sso.user.internal.rbac;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.rbac.Permissions;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pure grammar of a permission wildcard: {@code <resource>:*} (all actions on one tenant resource) and the
 * super token {@code *:*} (every permission, platform included). Getting validity wrong is a real escalation —
 * a wildcard that expands to a platform permission would hand a tenant a cross-tenant capability — so the
 * matrix below asserts both what expands and, adversarially, what must NOT be a valid wildcard at all.
 */
class PermissionPatternTest {

    // --- shape ---

    @Test
    void aResourceWildcardAndTheSuperTokenAreWildcardTokens() {
        assertThat(PermissionPattern.isWildcardToken("user:*")).isTrue();
        assertThat(PermissionPattern.isWildcardToken(Permissions.SUPER)).isTrue();
    }

    @Test
    void aConcretePermissionIsNotAWildcardToken() {
        assertThat(PermissionPattern.isWildcardToken("user:read")).isFalse();
        assertThat(PermissionPattern.isWildcardToken("scim:manage")).isFalse();
    }

    @Test
    void aThreeSegmentOrMalformedNameIsNotAWildcardToken() {
        assertThat(PermissionPattern.isWildcardToken("audit:read:*")).isFalse(); // action is not the wildcard
        assertThat(PermissionPattern.isWildcardToken("user")).isFalse();
        assertThat(PermissionPattern.isWildcardToken("*")).isFalse();
        assertThat(PermissionPattern.isWildcardToken("*:read")).isFalse(); // resource wildcard is not supported
    }

    @Test
    void theActionMustBeEXACTLYTheWildcardNotMerelyStartWithIt() {
        // "user:*:*" / "a:*:b" have a segment that starts with '*' but the token is malformed. If accepted, of()
        // would silently expand it as "user:*" — so the action must equal "*", never just begin with it.
        assertThat(PermissionPattern.isWildcardToken("user:*:*")).isFalse();
        assertThat(PermissionPattern.isWildcardToken("a:*:b")).isFalse();
        assertThat(PermissionPattern.isValid("user:*:*")).isFalse();
    }

    @Test
    void aWildcardIsCaseSensitiveAndUntrimmed() {
        // Resources are canonical lowercase and un-padded; leniency here would split validation from the
        // canonical stored authority — "USER:*" would validate while nothing matches the padded/upper name.
        assertThat(PermissionPattern.isValid("USER:*")).isFalse();
        assertThat(PermissionPattern.isValid(" user:*")).isFalse();
        assertThat(PermissionPattern.isWildcardToken("user:* ")).isFalse();
    }

    @Test
    void degenerateNullEmptyAndColonBoundariesAreNotWildcards() {
        assertThat(PermissionPattern.isWildcardToken(null)).isFalse();
        assertThat(PermissionPattern.isWildcardToken("")).isFalse();
        assertThat(PermissionPattern.isWildcardToken(":*")).isFalse();   // leading colon
        assertThat(PermissionPattern.isWildcardToken("user:")).isFalse(); // empty action
        assertThat(PermissionPattern.isWildcardToken(":read")).isFalse();
        assertThat(PermissionPattern.isValid("")).isFalse();
        assertThatThrownBy(() -> PermissionPattern.of(null)).isInstanceOf(BadRequestException.class);
    }

    // --- resource wildcard validity + expansion ---

    @Test
    void aResourceWildcardExpandsToItsTwoSegmentCatalogMembers() {
        assertThat(PermissionPattern.isValid("user:*")).isTrue();
        assertThat(PermissionPattern.of("user:*").expand()).containsExactlyInAnyOrder(
                Permissions.USER_READ, Permissions.USER_CREATE, Permissions.USER_UPDATE, Permissions.USER_DELETE);
    }

    @Test
    void theResourceWildcardCoversEveryActionOnThatResource() {
        // resource: has seven two-segment actions — a wildcard must cover all of them, none more.
        assertThat(PermissionPattern.of("resource:*").expand()).containsExactlyInAnyOrder(
                Permissions.RESOURCE_READ, Permissions.RESOURCE_CREATE, Permissions.RESOURCE_UPDATE,
                Permissions.RESOURCE_DELETE, Permissions.RESOURCE_ASSIGN_ADMIN,
                Permissions.RESOURCE_CREATE_TYPE, Permissions.RESOURCE_DELETE_TYPE);
    }

    @Test
    void aTwoActionResourceStillFormsAWildcard() {
        assertThat(PermissionPattern.of("portal-settings:*").expand()).containsExactlyInAnyOrder(
                Permissions.PORTAL_SETTINGS_READ, Permissions.PORTAL_SETTINGS_UPDATE);
    }

    // --- adversarial: what must NOT be a valid wildcard ---

    @Test
    void auditHasNoWildcardBecauseItsReadIsAMacroOverFinerScopes() {
        // audit:read expands (via the macro) to every category + PII; category perms exist precisely to grant
        // LESS. A wildcard that collapsed to audit:read would defeat that, so audit is excluded — it carries
        // three-segment sub-scopes (audit:read:pii, ...) that a two-segment wildcard cannot honour.
        assertThat(PermissionPattern.isValid("audit:*")).isFalse();
    }

    @Test
    void organizationHasNoWildcardBecauseItMixesPlatformAndTenantActions() {
        // organization:create/update/delete are PLATFORM-only; a tenant wildcard must never sweep a platform
        // permission in. Because the resource has platform members, no wildcard is formed over it at all.
        assertThat(PermissionPattern.isValid("organization:*")).isFalse();
    }

    @Test
    void anUnknownResourceHasNoWildcard() {
        assertThat(PermissionPattern.isValid("nonesuch:*")).isFalse();
    }

    @Test
    void ofRejectsAnInvalidWildcardTokenAsABadRequest() {
        assertThatThrownBy(() -> PermissionPattern.of("organization:*")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PermissionPattern.of("audit:*")).isInstanceOf(BadRequestException.class);
    }

    // --- super token ---

    @Test
    void theSuperTokenIsValidAndExpandsToTheWholeCatalogPlatformIncluded() {
        assertThat(PermissionPattern.isValid(Permissions.SUPER)).isTrue();
        assertThat(PermissionPattern.of(Permissions.SUPER).isSuper()).isTrue();
        Set<String> everything = PermissionPattern.of(Permissions.SUPER).expand();
        assertThat(everything)
                .containsExactlyInAnyOrderElementsOf(Permissions.ALL)
                .contains(Permissions.ORG_CREATE, Permissions.AUDIT_READ_PII); // platform + a finer sub-scope
    }

    @Test
    void onlyTheSuperTokenIsSuper() {
        assertThat(PermissionPattern.of("user:*").isSuper()).isFalse();
    }
}
