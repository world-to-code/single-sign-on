package com.example.sso.authpolicy;

import java.util.List;
import java.util.UUID;

/**
 * Write path of the authentication-policy engine: admin CRUD plus seeding/self-healing of the
 * non-editable Default fallback policy. Returns the public {@link AuthPolicyView}; the backing entity
 * stays module-internal.
 */
public interface AuthPolicyAdminService {

    /**
     * Ensures the fallback policy exists and is canonical (password then TOTP). Runs on every boot
     * and self-heals the Default if it was ever left in a bad state.
     */
    void seedDefault();

    List<AuthPolicyView> listAll();

    AuthPolicyView create(AuthPolicySpec spec);

    AuthPolicyView update(UUID id, AuthPolicyUpdate update);

    void delete(UUID id);
}
