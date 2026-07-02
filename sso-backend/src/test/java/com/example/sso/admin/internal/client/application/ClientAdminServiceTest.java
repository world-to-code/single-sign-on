package com.example.sso.admin.internal.client.application;

import com.example.sso.admin.internal.client.domain.OAuth2RegisteredClientEntity;
import com.example.sso.admin.internal.client.domain.OAuth2RegisteredClientRepository;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.ApplicationDeletedEvent;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.Optional;

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

    private RegisteredClientRepository registeredClients;
    private PasswordEncoder passwordEncoder;
    private OAuth2RegisteredClientRepository clientRows;
    private ApplicationEventPublisher events;
    private ClientAdminService service;

    @BeforeEach
    void setUp() {
        registeredClients = mock(RegisteredClientRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        clientRows = mock(OAuth2RegisteredClientRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ClientAdminService(registeredClients, passwordEncoder, clientRows, events);
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
    void deletingAnUnknownClientIsA404() {
        when(clientRows.findById(CLIENT_ROW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteClient(CLIENT_ROW_ID)).isInstanceOf(NotFoundException.class);
    }

    private OAuth2RegisteredClientEntity entity(String clientId) {
        OAuth2RegisteredClientEntity entity = mock(OAuth2RegisteredClientEntity.class);
        when(entity.getClientId()).thenReturn(clientId);
        return entity;
    }
}
