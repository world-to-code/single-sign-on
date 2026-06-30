package com.example.sso.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Generates and emails a short numeric code used for first-login email verification.
 */
@Service
public class EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JavaMailSender mailSender;
    private final long ttlMinutes; // single source of truth with the email factor's TTL, for the message text

    public EmailVerificationService(JavaMailSender mailSender,
                                    @Value("${sso.email-otp.ttl-minutes:10}") long ttlMinutes) {
        this.mailSender = mailSender;
        this.ttlMinutes = ttlMinutes;
    }

    public String generateCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    public void sendCode(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("Verify your email for Mini SSO");
        message.setText("Your verification code is: " + code + "\n\nIt expires in " + ttlMinutes + " minutes.");
        mailSender.send(message);
    }
}
