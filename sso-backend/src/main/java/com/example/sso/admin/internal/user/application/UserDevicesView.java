package com.example.sso.admin.internal.user.application;

import com.example.sso.webauthn.PasskeyView;

import java.util.List;

/** Admin view of a user's authentication devices: TOTP enrollment state and registered passkeys. */
public record UserDevicesView(boolean totpEnabled, List<PasskeyView> passkeys) {
}
