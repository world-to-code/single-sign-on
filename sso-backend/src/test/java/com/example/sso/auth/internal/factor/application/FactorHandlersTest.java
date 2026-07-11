package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@link FactorHandlers} registry: it indexes handlers by their {@link AuthFactor},
 * dispatches enrollment queries, and rejects a factor with no registered handler.
 */
@ExtendWith(MockitoExtension.class)
class FactorHandlersTest {

    @Mock private FactorHandler passwordHandler;
    @Mock private UserAccount user;

    private FactorHandlers registry() {
        when(passwordHandler.factor()).thenReturn(AuthFactor.PASSWORD);
        return new FactorHandlers(List.of(passwordHandler));
    }

    @Test
    void getReturnsTheHandlerRegisteredForTheFactor() {
        FactorHandlers registry = registry();

        assertThat(registry.get(AuthFactor.PASSWORD)).isSameAs(passwordHandler);
    }

    @Test
    void getRejectsAnUnsupportedFactor() {
        FactorHandlers registry = registry();

        assertThatThrownBy(() -> registry.get(AuthFactor.FIDO2)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void isEnrolledDelegatesToTheFactorHandler() {
        FactorHandlers registry = registry();
        lenient().when(passwordHandler.isEnrolled(user)).thenReturn(true);

        assertThat(registry.isEnrolled(AuthFactor.PASSWORD, user)).isTrue();
    }
}
