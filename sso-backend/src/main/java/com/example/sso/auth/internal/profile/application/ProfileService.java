package com.example.sso.auth.internal.profile.application;

import com.example.sso.auth.internal.login.application.CurrentUserProvider;

import com.example.sso.mfa.MfaService;
import com.example.sso.user.account.UserAccount;
import com.example.sso.webauthn.PasskeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Assembles the signed-in user's own "My Profile" roll-up (identity + security factors). */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final CurrentUserProvider currentUser;
    private final PasskeyService passkeys;
    private final MfaService mfaService;

    public ProfileView profile() {
        UserAccount user = currentUser.requireMfaComplete();
        int passkeyCount = passkeys.list(user).size();
        return ProfileView.of(user, mfaService.hasEnabledTotp(user.getId()), passkeyCount);
    }
}
