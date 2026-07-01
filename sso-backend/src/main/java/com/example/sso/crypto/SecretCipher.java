package com.example.sso.crypto;

/**
 * Crypto module's public contract for encrypting/decrypting secrets stored at rest. New values use
 * authenticated AES-256-GCM; legacy AES-256-CBC and bare plaintext remain readable for migration.
 * The implementation stays module-internal.
 */
public interface SecretCipher {

    String encrypt(String plaintext);

    String decrypt(String stored);
}
