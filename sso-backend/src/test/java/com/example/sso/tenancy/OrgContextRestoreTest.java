package com.example.sso.tenancy;

import com.example.sso.tenancy.internal.TransactionBoundRlsContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * What happens when the scoped context cannot be restored on the way out.
 *
 * <p>{@code withState} restores the outer organization on the held connection in a {@code finally}. That is a
 * {@code SET} on the transaction's connection, and Postgres refuses every command on a transaction its own
 * failure has already aborted — so the restore throws, and a throw from a {@code finally} DISCARDS the
 * exception in flight. A duplicate key reached callers as "current transaction is aborted", which matches no
 * {@code catch} written for it; a real race in the CSV import 500'd the whole request that way.
 *
 * <p>Swallowing it is only safe under an in-flight failure. With none, a failed restore leaves the connection
 * carrying the INNER organization's scope, and the next read on it would cross tenants — so that one has to
 * surface. Both directions are asserted here because only one of them is about losing information; the other
 * is about isolation.
 */
class OrgContextRestoreTest {

    private static final UUID ORG = UUID.randomUUID();

    /**
     * A context whose Nth binder call fails, counting every call from one. {@code withState} makes two — the
     * push on the way in and the restore on the way out — so which number is the restore depends on whether
     * the test bound an outer organization first.
     */
    private OrgContext contextFailingOn(int failingCall) {
        TransactionBoundRlsContext binder = mock(TransactionBoundRlsContext.class);
        int[] calls = {0};
        doAnswer(invocation -> {
            if (++calls[0] == failingCall) {
                throw new IllegalStateException("current transaction is aborted");
            }
            return null;
        }).when(binder).apply(anyBoolean(), anyString());
        return new OrgContext(providerOf(binder));
    }

    @Test
    void aFailedRestoreDoesNotReplaceTheExceptionThatCausedIt() {
        OrgContext context = contextFailingOn(2);

        assertThatThrownBy(() -> context.callInOrg(ORG, () -> {
            throw new IllegalArgumentException("duplicate key");
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate key")
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .as("the restore failure is kept, not discarded")
                        .hasSize(1));
    }

    /**
     * Nothing failed, so a restore that fails is a real one: the connection would otherwise keep the inner
     * organization's scope and serve the outer caller another tenant's rows.
     */
    @Test
    void aFailedRestoreSurfacesWhenTheActionSucceeded() {
        OrgContext context = contextFailingOn(2);

        assertThatThrownBy(() -> context.callInOrg(ORG, () -> "ok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("current transaction is aborted");
    }

    /** The ordinary path still restores, and still returns. */
    @Test
    void aSuccessfulActionRestoresAndReturns() {
        OrgContext context = contextFailingOn(0);

        assertThat(context.callInOrg(ORG, () -> "ok")).isEqualTo("ok");
        assertThat(context.currentOrg()).isEmpty();
    }

    /** And the thread-local state is restored even when the connection restore fails. */
    @Test
    void theBoundOrganizationIsRestoredEvenWhenTheConnectionIsNot() {
        OrgContext context = contextFailingOn(3);   // bindOrg=1, the push=2, the restore=3
        UUID outer = UUID.randomUUID();

        context.bindOrg(outer);
        assertThatThrownBy(() -> context.callInOrg(ORG, () -> {
            throw new IllegalArgumentException("duplicate key");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(context.currentOrg()).contains(outer);
    }

    private ObjectProvider<TransactionBoundRlsContext> providerOf(TransactionBoundRlsContext binder) {
        return new ObjectProvider<>() {
            @Override
            public TransactionBoundRlsContext getObject() {
                return binder;
            }

            @Override
            public TransactionBoundRlsContext getObject(Object... args) {
                return binder;
            }

            @Override
            public TransactionBoundRlsContext getIfAvailable() {
                return binder;
            }

            @Override
            public TransactionBoundRlsContext getIfUnique() {
                return binder;
            }
        };
    }
}
