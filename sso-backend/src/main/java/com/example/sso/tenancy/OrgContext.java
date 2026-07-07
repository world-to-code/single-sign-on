package com.example.sso.tenancy;

import com.example.sso.tenancy.internal.TransactionBoundRlsContext;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The active tenant context for the current thread, consulted when a JDBC connection is bound to a
 * transaction to set the Postgres RLS GUCs ({@code app.current_org} / {@code app.platform}). States:
 * <ul>
 *   <li><b>org-bound</b> — scoped to one organization; RLS shows only that org's rows;</li>
 *   <li><b>platform</b> — cross-org (super-admin, seeder, background jobs); RLS shows all rows;</li>
 *   <li><b>customer</b> — a customer (고객사) console: manages the customer's orgs on GLOBAL tables and drills
 *       into one org at a time. It carries NO org GUC, so for org-scoped tables RLS is fail-closed exactly like
 *       <em>unset</em> — a customer context can read no org's scoped rows without an explicit {@link #bindOrg}
 *       (gated by {@code canManage}). The {@code customerId} is an authz/session-layer marker only, NEVER a GUC,
 *       so no org-scoped RLS policy changes and the per-org boundary is untouched;</li>
 *   <li><b>unset</b> — no context; RLS is fail-closed (no GUC → no rows) for org-scoped tables.</li>
 * </ul>
 * Scoped operations run inside {@link #callInOrg}/{@link #callAsPlatform}, which save and restore the
 * previous state so nesting (and the request-scoped context) is not clobbered.
 */
@Component
public class OrgContext {

    private record State(UUID orgId, boolean platform, UUID customerId) {
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

    /** The customer (고객사) whose console this context is, or empty when org-bound / platform / unset. */
    public Optional<UUID> currentCustomer() {
        State state = holder.get();
        return state == null ? Optional.empty() : Optional.ofNullable(state.customerId());
    }

    // --- request-lifecycle binding (a servlet filter sets one at request start and clears at the end;
    //     nested callInOrg/callAsPlatform scopes still save-and-restore around it) --------------------

    /** Binds the thread to {@code orgId} for the rest of the request. */
    public void bindOrg(UUID orgId) {
        holder.set(new State(orgId, false, null));
        syncConnection();
    }

    /** Binds the thread to the cross-org platform context for the rest of the request. */
    public void enterPlatform() {
        holder.set(new State(null, true, null));
        syncConnection();
    }

    /**
     * Binds the thread to a customer (고객사) console context for the rest of the request: NOT platform, and no
     * org GUC (org-scoped RLS stays fail-closed). The {@code customerId} is a session/authz marker only.
     */
    public void enterCustomer(UUID customerId) {
        holder.set(new State(null, false, customerId));
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
        return withState(new State(orgId, false, null), action);
    }

    /** Runs {@code action} in the cross-org platform context (RLS bypass), restoring the prior context. */
    public <T> T callAsPlatform(Supplier<T> action) {
        return withState(new State(null, true, null), action);
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
        syncConnection(); // push the scoped context onto a held tx connection (frozen at acquisition otherwise)
        try {
            return action.get();
        } finally {
            if (previous == null) {
                holder.remove();
            } else {
                holder.set(previous);
            }
            syncConnection(); // restore the outer context on the held connection too
        }
    }
}
