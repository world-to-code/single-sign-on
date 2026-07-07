package com.example.sso.user;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Binds the customer (고객사) a login must resolve the user WITHIN, for the duration of a password/passkey
 * authentication. Spring Security's password {@code AuthenticationProvider} calls
 * {@code UserDetailsService.loadUserByUsername} with only a username; once usernames are unique per-customer
 * (not globally), the same username can denote different users in different customers, so the resolver must
 * know which customer's directory to look in — otherwise a username shared across customers could
 * authenticate against the WRONG account (an auth bypass).
 *
 * <p>The login orchestrator binds the target's customer with {@link #within} around the authentication and
 * the authority-loading step; the resolver reads it via {@link #current}. Scope states:
 * <ul>
 *   <li><b>unset</b> — no login in progress: the resolver falls back to a plain global lookup;</li>
 *   <li><b>global</b> — {@code customerId} is null: the apex/platform super-admin path (customer-less
 *       accounts only);</li>
 *   <li><b>a customer</b> — resolves that customer's user, falling back to a global (customer-less) account
 *       so the platform super-admin still signs in through a tenant they belong to.</li>
 * </ul>
 */
@Component
public class LoginResolutionScope {

    /** A bound login-resolution scope; {@code customerId} may be null (global-only resolution). */
    public record Scope(UUID customerId) {
    }

    private final ThreadLocal<Scope> holder = new ThreadLocal<>();

    /**
     * Runs {@code action} with the resolution scope bound to {@code customerId} (null = global), restoring
     * the previous scope afterwards so nesting is safe.
     */
    public <T> T within(UUID customerId, Supplier<T> action) {
        Scope previous = holder.get();
        holder.set(new Scope(customerId));
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
}
