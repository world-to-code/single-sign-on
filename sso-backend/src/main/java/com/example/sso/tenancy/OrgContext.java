package com.example.sso.tenancy;

import com.example.sso.tenancy.internal.TransactionBoundRlsContext;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The active tenant context for the current thread, consulted when a JDBC connection is bound to a
 * transaction to set the Postgres RLS GUCs ({@code app.current_org} / {@code app.platform}). States:
 * <ul>
 *   <li><b>org-bound</b> — scoped to one organization; RLS shows only that org's rows;</li>
 *   <li><b>platform</b> — cross-org (super-admin, seeder, background jobs); RLS shows all rows;</li>
 *   <li><b>unset</b> — no context; RLS is fail-closed (no GUC → no rows) for org-scoped tables.</li>
 * </ul>
 * Scoped operations run inside {@link #callInOrg}/{@link #callAsPlatform}, which save and restore the
 * previous state so nesting (and the request-scoped context) is not clobbered.
 */
@Component
public class OrgContext {

    private record State(UUID orgId, boolean platform) {
    }

    private final ThreadLocal<State> holder = new ThreadLocal<>();

    // Lazy (ObjectProvider) to break the cycle: the binder needs the EntityManagerFactory, whose DataSource
    // (OrgAwareDataSource) needs this OrgContext. Resolved on first use, long after construction.
    private final ObjectProvider<TransactionBoundRlsContext> connectionBinder;

    public OrgContext(ObjectProvider<TransactionBoundRlsContext> connectionBinder) {
        this.connectionBinder = connectionBinder;
    }

    /** The bound organization, or empty when platform/unset. */
    public Optional<UUID> currentOrg() {
        State state = holder.get();
        return state == null ? Optional.empty() : Optional.ofNullable(state.orgId());
    }

    /** Whether the current context is the cross-org platform context. */
    public boolean isPlatform() {
        State state = holder.get();
        return state != null && state.platform();
    }

    // --- request-lifecycle binding (a servlet filter sets one at request start and clears at the end;
    //     nested callInOrg/callAsPlatform scopes still save-and-restore around it) --------------------

    /** Binds the thread to {@code orgId} for the rest of the request. */
    public void bindOrg(UUID orgId) {
        holder.set(new State(orgId, false));
        syncConnection();
    }

    /** Binds the thread to the cross-org platform context for the rest of the request. */
    public void enterPlatform() {
        holder.set(new State(null, true));
        syncConnection();
    }

    /** Clears the bound context (call in a {@code finally} at request end). */
    public void clear() {
        holder.remove();
        syncConnection();
    }

    // Re-apply the current context to any connection already held by an active transaction (no-op otherwise).
    private void syncConnection() {
        State state = holder.get();
        boolean platform = state != null && state.platform();
        String org = (state != null && state.orgId() != null && !platform) ? state.orgId().toString() : "";
        connectionBinder.ifAvailable(binder -> binder.apply(platform, org));
    }

    /** Runs {@code action} bound to {@code orgId} (RLS scoped to that org), restoring the prior context. */
    public <T> T callInOrg(UUID orgId, Supplier<T> action) {
        return withState(new State(orgId, false), action);
    }

    /** Runs {@code action} in the cross-org platform context (RLS bypass), restoring the prior context. */
    public <T> T callAsPlatform(Supplier<T> action) {
        return withState(new State(null, true), action);
    }

    public void runInOrg(UUID orgId, Runnable action) {
        callInOrg(orgId, () -> {
            action.run();
            return null;
        });
    }

    public void runAsPlatform(Runnable action) {
        callAsPlatform(() -> {
            action.run();
            return null;
        });
    }

    private <T> T withState(State state, Supplier<T> action) {
        State previous = holder.get();
        holder.set(state);
        Throwable failure = null;
        try {
            // Inside the try: a push that throws must still hand the thread back on the OUTER org. A request
            // thread would be rescued by the filters that clear in a finally, but an @Async or scheduled
            // thread has none — it would stay bound to the inner org for whatever ran on it next.
            syncConnection();
            return action.get();
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            if (previous == null) {
                holder.remove();
            } else {
                holder.set(previous);
            }
            restoreConnection(failure);
        }
    }

    /**
     * Restores the outer context on the held connection, without letting that restore replace the exception
     * that made it necessary.
     *
     * <p>The usual reason the restore cannot run is that the action's OWN failure aborted the transaction:
     * Postgres then refuses every command, including this {@code SET}. Thrown from a {@code finally} it
     * discards the in-flight exception, so a duplicate key — the one thing that said what went wrong —
     * reaches the caller as "current transaction is aborted" instead, and every {@code catch} for a specific
     * type silently stops matching. A real race in the CSV import surfaced exactly that way.
     *
     * <p>Swallowing leaves the connection carrying the INNER organization, and the GUC is session-lifetime
     * ({@code set_config(..., false)}), so what keeps that from reaching the next borrower is NOT this method.
     * It is two things elsewhere, named here because a change to either turns this into a cross-tenant read:
     * a {@code SET} issued inside a transaction is reverted when that transaction rolls back, and
     * {@link com.example.sso.tenancy.internal.OrgAwareDataSource} re-applies the context UNCONDITIONALLY on
     * every acquisition — writing empty, which is fail-closed, when there is none. An "only SET when it
     * changed" optimization there would make a swallowed restore leak.
     *
     * <p>With no failure in flight the restore must surface instead: the thread-local then says OUTER while
     * the connection says INNER, and every tier check reads the thread-local. The transaction is also doomed
     * so that nobody who catches and carries on can commit work done under a scope we cannot name.
     */
    private void restoreConnection(Throwable failure) {
        try {
            syncConnection();
        } catch (RuntimeException restoreFailed) {
            doomTransaction();
            if (failure == null) {
                throw restoreFailed;
            }
            failure.addSuppressed(restoreFailed);
        }
    }

    /** The connection's scope is now unknown, so nothing done under it may commit. */
    private void doomTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }
}
