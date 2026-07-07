package com.example.sso.user;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Binds the organization (the tenant) a login must resolve the user WITHIN, for the duration of a
 * password/passkey authentication. Spring Security's password {@code AuthenticationProvider} calls
 * {@code UserDetailsService.loadUserByUsername} with only a username; once usernames are unique per-organization
 * (not globally), the same username can denote different users in different organizations, so the resolver must
 * know which organization's directory to look in — otherwise a username shared across organizations could
 * authenticate against the WRONG account (an auth bypass).
 *
 * <p>The login orchestrator binds the target's organization with {@link #within} around the authentication and
 * the authority-loading step; the resolver reads it via {@link #current}. Scope states:
 * <ul>
 *   <li><b>unset</b> — no login in progress: the resolver falls back to the request's session context;</li>
 *   <li><b>global</b> — {@code orgId} is null: the apex/platform super-admin path (org-less accounts only);</li>
 *   <li><b>an organization</b> — resolves that org's user, falling back to a global (org-less) account so the
 *       platform super-admin still signs in through a tenant they belong to.</li>
 * </ul>
 */
@Component
public class LoginResolutionScope {

    /** A bound login-resolution scope; {@code orgId} may be null (global-only resolution). */
    public record Scope(UUID orgId) {
    }

    private final ThreadLocal<Scope> holder = new ThreadLocal<>();

    /**
     * Runs {@code action} with the resolution scope bound to {@code orgId} (null = global), restoring the
     * previous scope afterwards so nesting is safe.
     */
    public <T> T within(UUID orgId, Supplier<T> action) {
        Scope previous = holder.get();
        holder.set(new Scope(orgId));
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

    /** The bound scope, or empty when no login resolution is in progress. */
    public Optional<Scope> current() {
        return Optional.ofNullable(holder.get());
    }

    /** A handle that restores the previous scope when closed — for a servlet filter binding the scope across
     *  a whole request (try-with-resources). */
    public interface Restore extends AutoCloseable {
        @Override
        void close();
    }

    /** Binds the scope to {@code orgId} (null = global) until the returned handle is closed, restoring the
     *  previous scope then — so a filter can scope an entire login request. */
    public Restore open(UUID orgId) {
        Scope previous = holder.get();
        holder.set(new Scope(orgId));
        return () -> {
            if (previous == null) {
                holder.remove();
            } else {
                holder.set(previous);
            }
        };
    }
}
