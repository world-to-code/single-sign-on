package com.example.sso.mfa.internal.application;

import com.example.sso.mfa.QrCodeService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Default {@link QrCodeService}. Renders a string (e.g. an {@code otpauth://} URI) as a PNG QR code
 * data URI so it can be scanned directly by an authenticator app from the enrollment page.
 */
@Service
public class QrCodeServiceImpl implements QrCodeService {

    private final int size;

    public QrCodeServiceImpl(@Value("${sso.totp.qr-size:220}") int size) {
        this.size = size;
    }

    @Override
    public String pngDataUri(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);

            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render QR code", e);
        }
    }
}
