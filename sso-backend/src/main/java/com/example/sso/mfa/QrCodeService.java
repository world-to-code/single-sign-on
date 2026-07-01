package com.example.sso.mfa;

/**
 * MFA module's public contract for rendering a string (e.g. an {@code otpauth://} URI) as a PNG QR
 * code data URI. The implementation stays module-internal.
 */
public interface QrCodeService {

    String pngDataUri(String content);
}
