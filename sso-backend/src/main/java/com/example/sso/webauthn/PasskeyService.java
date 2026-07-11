package com.example.sso.webauthn;

import com.example.sso.user.account.UserAccount;

import java.util.List;

/**
 * WebAuthn module's public contract: self-service listing and deletion of a user's passkeys. The
 * implementation stays module-internal.
 */
public interface PasskeyService {

    List<PasskeyView> list(UserAccount user);

    void delete(UserAccount user, String credentialId);
}
