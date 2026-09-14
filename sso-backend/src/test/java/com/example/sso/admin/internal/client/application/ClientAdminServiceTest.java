package com.example.sso.admin.internal.client.application;

import com.example.sso.admin.internal.client.domain.OAuth2RegisteredClientEntity;
import com.example.sso.admin.internal.client.domain.OAuth2RegisteredClientRepository;
import com.example.sso.crypto.ClientSecretHasher;
import com.example.sso.crypto.internal.application.ClientSecretHasherImpl;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.application.ApplicationDeletedEvent;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.util.Set;
import com.example.sso.admin.internal.shared.application.ActingAdmin;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClientAdminService#deleteClient}. Focus: the first-party admin-console client is
 * protected from deletion (409, no side effects), a normal delete both removes the row and publishes an
 * {@link ApplicationDeletedEvent} (verify interactions), and a missing client is a 404.
 */
class ClientAdminServiceTest {

    private static final String CLIENT_ROW_ID = "row-1";
    private static final String TEST_SALT = "5c0744940b5c369b";

    private RegisteredClientRepository registeredClients;
    private final ClientSecretHasher clientSecrets = new ClientSecretHasherImpl("test-master-password", TEST_SALT);
    private OAuth2RegisteredClientRepository clientRows;
    private ApplicationEventPublisher events;
    private ClientAdminService service;

    @BeforeEach
    void setUp() {
        registeredClients = mock(RegisteredClientRepository.class);
        clientRows = mock(OAuth2RegisteredClientRepository.class);
        events = mock(ApplicationEventPublisher.class);
        OrgContext orgContext = mock(OrgContext.class);
        when(orgContext.currentOrg()).thenReturn(Optional.<UUID>empty()); // platform tier — the test clients are global
        // A mocked ActingAdmin holds no authorities, so nothing here may grant a response scope — right for
        // these cases, none of which asks for one. That guard is covered against a real DB in
        // ResponseScopeGrantIT, where a mock could not tell a refusal from an absent check.
        service = new ClientAdminService(registeredClients, clientSecrets, clientRows,
                new OrgTierGuard(orgContext), events, mock(ActingAdmin.class));
    }

    @Test
    void deletingTheProtectedAdminConsoleClientIsRejectedWith409() {
        OAuth2RegisteredClientEntity adminConsole = entity(AdminPortalSeeder.CLIENT_ID);
        when(clientRows.findById(CLIENT_ROW_ID)).thenReturn(Optional.of(adminConsole));

        assertThatThrownBy(() -> service.deleteClient(CLIENT_ROW_ID)).isInstanceOf(ConflictException.class);
        verify(clientRows, never()).deleteById(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void deletingAnOrdinaryClientRemovesTheRowAndPublishesTheDeletedEvent() {
        OAuth2RegisteredClientEntity shop = entity("shop");
        when(clientRows.findById(CLIENT_ROW_ID)).thenReturn(Optional.of(shop));

        service.deleteClient(CLIENT_ROW_ID);

        verify(clientRows).deleteById(CLIENT_ROW_ID);
        verify(events).publishEvent(new ApplicationDeletedEvent(CLIENT_ROW_ID));
    }

    @Test
    void createRejectsAClientAuthenticationMethodTheTokenEndpointCannotEnforce() {
        // tls_client_auth has a framework provider but no mTLS is terminated at the edge, so a client saved
        // with it could never authenticate — reject it rather than persist a silently-unusable client.
        assertThatThrownBy(() -> service.createClient(request(Set.of("tls_client_auth"))))
                .isInstanceOf(BadRequestException.class);
        verify(registeredClients, never()).save(any());
    }

    @Test
    void aConfidentialClientsGeneratedSecretIsStoredAsAKeyedHashAndReturnedOnce() {
        CreateClientRequest confidential = new CreateClientRequest("cid", null, Set.of(), Set.of(), Set.of(),
                Set.of("client_credentials"), Set.of("client_secret_basic"), false, false, false, null, null, null,
                null, false, null, null, null, null, null, false, null, null, null, false);

        ClientCreated created = service.createClient(confidential);

        ArgumentCaptor<RegisteredClient> saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClients).save(saved.capture());
        String stored = saved.getValue().getClientSecret();
        // The server generated this secret, so the fast keyed hash is sound — and the stored form verifies it.
        assertThat(clientSecrets.handles(stored)).isTrue();
        assertThat(clientSecrets.matches(created.clientSecret(), stored)).isTrue();
        assertThat(stored).doesNotContain(created.clientSecret());
    }

    @Test
    void deletingAnUnknownClientIsA404() {
        when(clientRows.findById(CLIENT_ROW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteClient(CLIENT_ROW_ID)).isInstanceOf(NotFoundException.class);
    }

    private CreateClientRequest request(Set<String> authMethods) {
        return new CreateClientRequest("cid", null, Set.of(), Set.of(), Set.of(), Set.of(), authMethods,
                false, false, false, null, null, null, null, false, null, null, null, null, null, false, null,
                null, null, false);
    }

    private OAuth2RegisteredClientEntity entity(String clientId) {
        OAuth2RegisteredClientEntity entity = mock(OAuth2RegisteredClientEntity.class);
        when(entity.getClientId()).thenReturn(clientId);
        return entity;
    }
}
