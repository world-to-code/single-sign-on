package com.example.sso.webauthn;

import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.AppUser;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Self-service management (list + delete) of a user's WebAuthn passkeys. There is a single
 * passkey store (Spring Security WebAuthn); each passkey works for both passwordless login and
 * the FIDO2 second factor.
 */
@Service
public class PasskeyService {

    private final UserCredentialRepository credentials;
    private final PublicKeyCredentialUserEntityRepository userEntities;

    public PasskeyService(UserCredentialRepository credentials,
                          PublicKeyCredentialUserEntityRepository userEntities) {
        this.credentials = credentials;
        this.userEntities = userEntities;
    }

    @Transactional(readOnly = true)
    public List<PasskeyView> list(AppUser user) {
        PublicKeyCredentialUserEntity entity = userEntities.findByUsername(user.getUsername());
        if (entity == null) {
            return List.of();
        }
        return credentials.findByUserId(entity.getId()).stream()
                .map(c -> new PasskeyView(c.getCredentialId().toBase64UrlString(), c.getLabel(),
                        c.getCreated() == null ? null : c.getCreated().toString(),
                        c.getLastUsed() == null ? null : c.getLastUsed().toString()))
                .toList();
    }

    @Transactional
    public void delete(AppUser user, String credentialId) {
        PublicKeyCredentialUserEntity entity = userEntities.findByUsername(user.getUsername());
        CredentialRecord owned = entity == null ? null : credentials.findByUserId(entity.getId()).stream()
                .filter(c -> c.getCredentialId().toBase64UrlString().equals(credentialId))
                .findFirst().orElse(null);
        if (owned == null) {
            throw new NotFoundException("passkey not found");
        }
        credentials.delete(owned.getCredentialId());
    }
}
