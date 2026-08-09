package com.example.sso.oidc;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ending a user's sessions must also end the grants those sessions handed to applications.
 *
 * <p>Session termination deletes the IdP's own session and asks every relying party to drop theirs. It does
 * not touch the REFRESH TOKEN the relying party is holding, and refreshing never passes through
 * {@code /oauth2/authorize} — so a disabled, locked or held account kept minting access tokens for the whole
 * refresh lifetime (a week by default) while the console reported it signed out. That is the shape the
 * zero-trust rule names: the credential is gone and the access is not.
 *
 * <p>The cross-tenant case is the one that decides how this may be implemented. A username is unique only
 * WITHIN an organization, so revoking by principal name alone would delete a same-named stranger's grants in
 * another tenant. The authorization row carries no org of its own — its tenant is the one owning the client
 * it was issued for.
 */
class OidcAuthorizationRevocationIT extends AbstractIntegrationTest {

    @Autowired
    OidcAuthorizations authorizations;
    @Autowired
    OAuth2AuthorizationService authorizationService;
    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    UserService users;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void revokingRemovesTheUsersGrantsToTheirTenantsApplications() {
        UUID org = tenant();
        UserAccount user = userIn(org, "alice");
        String id = grant(org, user.getUsername());

        assertThat(authorizations.revokeForUser(user.getUsername(), org)).isEqualTo(1);

        assertThat(exists(id)).as("the refresh token is gone with it").isFalse();
    }

    /**
     * The hazard that decides the implementation. Two tenants may each have an "alice", and the authorization
     * table keys on the bare name — so a revocation scoped only by principal would sign out a stranger.
     */
    @Test
    void aSameNamedUserInAnotherTenantKeepsTheirs() {
        UUID mine = tenant();
        UUID theirs = tenant();
        // Literally the SAME username in two organizations — which per-org uniqueness (V68) permits, and
        // which is the whole hazard. An earlier version of this test suffixed each name uniquely and so
        // proved nothing: deleting the tenant scope left it green.
        String shared = "alice-" + suffix() + "@example.com";
        UserAccount here = userNamed(mine, shared);
        UserAccount stranger = userNamed(theirs, shared);
        String mineId = grant(mine, here.getUsername());
        String theirsId = grant(theirs, stranger.getUsername());

        authorizations.revokeForUser(here.getUsername(), mine);

        assertThat(exists(mineId)).isFalse();
        assertThat(exists(theirsId)).as("another tenant's grant is untouched").isTrue();
    }

    @Test
    void anotherUsersGrantInTheSameTenantSurvives() {
        UUID org = tenant();
        UserAccount target = userIn(org, "alice");
        UserAccount bystander = userIn(org, "bob");
        String targetId = grant(org, target.getUsername());
        String bystanderId = grant(org, bystander.getUsername());

        authorizations.revokeForUser(target.getUsername(), org);

        assertThat(exists(targetId)).isFalse();
        assertThat(exists(bystanderId)).isTrue();
    }

    /** Nothing to revoke is not a failure — every access change calls this, and most users hold no grant. */
    @Test
    void revokingWhenThereIsNothingToRevokeReportsZero() {
        UUID org = tenant();
        UserAccount user = userIn(org, "alice");

        assertThat(authorizations.revokeForUser(user.getUsername(), org)).isZero();
    }

    /**
     * The access change itself must drive it, not a caller remembering to. Disabling an account is the
     * plainest revocation there is, and it publishes the same event a hold and a role change do.
     */
    @Test
    void disablingAnAccountRevokesItsGrants() {
        UUID org = tenant();
        UserAccount user = userIn(org, "alice");
        String id = grant(org, user.getUsername());

        orgContext.runInOrg(org, () -> users.disable(user.getId()));

        assertThat(exists(id)).isFalse();
    }

    /** A grant issued to a GLOBAL client for a platform account is revoked under the platform tier. */
    @Test
    void aPlatformAccountsGrantIsRevokedToo() {
        UserAccount global = users.createUser(new NewUser(
                name("global"), name("global") + "@example.com", "Global", "S3cret!pw", Set.of()));
        cleanups.add(() -> orgContext.runAsPlatform(() -> users.delete(global.getId())));
        String id = grant(null, global.getUsername());

        assertThat(authorizations.revokeForUser(global.getUsername(), null)).isEqualTo(1);
        assertThat(exists(id)).isFalse();
    }

    private boolean exists(String authorizationId) {
        Integer count = ownerJdbc().queryForObject(
                "select count(*) from oauth2_authorization where id = ?", Integer.class, authorizationId);
        return count != null && count > 0;
    }

    /** An authorization for {@code principal}, issued by a client owned by {@code org} (null = platform). */
    private String grant(UUID org, String principal) {
        RegisteredClient client = orgContext.callInOrg(org, () -> {
            RegisteredClient registered = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId("app-" + suffix())
                    .clientSecret("{noop}s3cret-" + UUID.randomUUID())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri("https://app.example.com/cb")
                    .scope("openid")
                    .build();
            clients.save(registered);
            return registered;
        });
        cleanups.add(() -> ownerJdbc().update(
                "delete from oauth2_registered_client where id = ?", client.getId()));

        Instant now = Instant.now();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName(principal)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid"))
                .token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                        "access-" + suffix(), now, now.plusSeconds(1800)))
                .token(new OAuth2RefreshToken("refresh-" + suffix(), now, now.plusSeconds(604800)))
                .build();
        orgContext.runInOrg(org, () -> authorizationService.save(authorization));
        cleanups.add(() -> ownerJdbc().update("delete from oauth2_authorization where id = ?", authorization.getId()));
        return authorization.getId();
    }

    private UUID tenant() {
        UUID org = organizations.create(new NewOrganization(name("revoke"), "revoke")).id();
        cleanups.add(() -> orgContext.runAsPlatform(
                () -> ownerJdbc().update("delete from organization where id = ?", org)));
        return org;
    }

    private UserAccount userIn(UUID org, String localName) {
        return userNamed(org, localName + "-" + suffix() + "@example.com");
    }

    /** A user with EXACTLY this username in that org — two tenants may hold the same one. */
    private UserAccount userNamed(UUID org, String username) {
        UserAccount account = orgContext.callInOrg(org, () -> users.createUser(
                new NewUser(username, username, "U", "S3cret!pw", Set.of()), org));
        cleanups.add(() -> orgContext.runAsPlatform(() -> users.delete(account.getId())));
        return account;
    }

    private String name(String prefix) {
        return prefix + "-" + suffix();
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
