package com.example.sso.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The active tenant context for the current thread, consulted when a JDBC connection is bound to a
 * transaction to set the Postgres RLS GUCs ({@code app.current_org} / {@code app.platform}). Three states:
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
        try {
            return action.get();
        } finally {
            if (previous == null) {
                holder.remove();
            } else {
                holder.set(previous);
            }
        }
    }
}
