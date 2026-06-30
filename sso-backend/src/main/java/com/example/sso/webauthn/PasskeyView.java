package com.example.sso.webauthn;

/** A registered passkey as shown to its owner. */
public record PasskeyView(String id, String label, String createdAt, String lastUsedAt) {
}
