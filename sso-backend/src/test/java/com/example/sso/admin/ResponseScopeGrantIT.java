package com.example.sso.admin;

import com.example.sso.admin.internal.client.application.ClientAdminService;
import com.example.sso.admin.internal.client.application.CreateClientRequest;
import com.example.sso.response.ResponseScopes;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registering a client must not be a way to acquire a capability you do not hold.
 *
 * <p>A response scope hands its bearer power over ACCOUNTS — ending sessions, holding people out — with none
 * of the console's self-protection guards in the way, because there is no acting administrator behind a
 * machine token to protect anybody from. So {@code oidc-client:create} alone would otherwise be strictly
 * larger than it appears: an administrator with no authority over a single user could mint a credential with
 * authority over all of them.
 *
 * <p>The permitted case is asserted too. A guard that refuses everybody is not a guard, it is an outage, and
 * a suite of refusals cannot tell the two apart.
 */
class ResponseScopeGrantIT extends AbstractIntegrationTest {

    @Autowired
    ClientAdminService clients;
    @Autowired
    UserService users;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void anAdministratorWhoMayActOnUsersMayGrantAResponseScope() {
        signedInWith(Permissions.CLIENT_CREATE, Permissions.USER_UPDATE);
        String clientId = "xdr-" + suffix();

        assertThatCode(() -> clients.createClient(request(clientId, ResponseScopes.HOLD)))
                .doesNotThrowAnyException();
        cleanups.add(() -> deleteClient(clientId));

        assertThat(scopesOf(clientId)).contains(ResponseScopes.HOLD);
    }

    @Test
    void registeringClientsIsNotEnoughAuthorityToGrantOne() {
        signedInWith(Permissions.CLIENT_CREATE);
        String clientId = "xdr-" + suffix();

        assertThatThrownBy(() -> clients.createClient(request(clientId, ResponseScopes.HOLD)))
                .isInstanceOf(ForbiddenException.class);
        assertThat(exists(clientId)).as("the refusal happens before anything is written").isFalse();
    }

    /** Every verb's scope, not just the one somebody thought of — the guard is on the namespace. */
    @Test
    void theSameHoldsForEveryResponseScope() {
        signedInWith(Permissions.CLIENT_CREATE);

        for (String scope : List.of(ResponseScopes.SESSION_TERMINATE, ResponseScopes.HOLD_LIFT)) {
            assertThatThrownBy(() -> clients.createClient(request("xdr-" + suffix(), scope)))
                    .as(scope).isInstanceOf(ForbiddenException.class);
        }
    }

    /** An ordinary client is unaffected — this guards one namespace, not client registration generally. */
    @Test
    void anOrdinaryClientStillRegistersWithoutIt() {
        signedInWith(Permissions.CLIENT_CREATE);
        String clientId = "app-" + suffix();

        assertThatCode(() -> clients.createClient(request(clientId, "openid"))).doesNotThrowAnyException();
        cleanups.add(() -> deleteClient(clientId));
    }

    private CreateClientRequest request(String clientId, String scope) {
        return new CreateClientRequest(clientId, clientId,
                Set.of(),                       // redirectUris — a machine client has nowhere to come back to
                Set.of(),                       // postLogoutRedirectUris
                Set.of(scope),
                Set.of("client_credentials"),
                Set.of("client_secret_basic"),
                false, false, false,            // publicClient / requireConsent / requireProofKey
                null, null, null, null,         // token lifetimes
                false,                          // reuseRefreshTokens
                null, null, null, null, null,   // formats, algorithms, jwkSetUrl, x509SubjectDn
                false,                          // x509BoundAccessTokens
                null, null, null,               // clientSecretDays, initiateLoginUri, backchannelLogoutUri
                false);                         // backchannelLogoutSessionRequired
    }

    private void signedInWith(String... permissions) {
        String username = "granter-" + suffix();
        UserAccount actor = users.createUser(
                new NewUser(username, username + "@example.com", "Granter", "S3cret!pw", Set.of()));
        cleanups.add(() -> users.delete(actor.getId()));
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Roles.ADMIN));
        for (String permission : permissions) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    private List<String> scopesOf(String clientId) {
        return ownerJdbc().queryForList(
                "select scopes from oauth2_registered_client where client_id = ?", String.class, clientId);
    }

    private boolean exists(String clientId) {
        Integer count = ownerJdbc().queryForObject(
                "select count(*) from oauth2_registered_client where client_id = ?", Integer.class, clientId);
        return count != null && count > 0;
    }

    private void deleteClient(String clientId) {
        ownerJdbc().update("delete from oauth2_registered_client where client_id = ?", clientId);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
