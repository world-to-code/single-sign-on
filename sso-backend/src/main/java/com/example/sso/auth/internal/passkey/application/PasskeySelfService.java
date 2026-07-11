package com.example.sso.auth.internal.passkey.application;

import com.example.sso.auth.internal.login.application.CurrentUserProvider;

import com.example.sso.user.UserAccount;
import com.example.sso.webauthn.PasskeyService;
import com.example.sso.webauthn.PasskeyView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Self-service passkey management for the signed-in user. */
@Service
@RequiredArgsConstructor
public class PasskeySelfService {

    private final CurrentUserProvider currentUser;
    private final PasskeyService passkeys;

    public List<PasskeyView> list() {
        return passkeys.list(currentUser.requireMfaComplete());
    }

    public void delete(String credentialId) {
        UserAccount user = currentUser.requireMfaComplete();
        passkeys.delete(user, credentialId);
    }
}
