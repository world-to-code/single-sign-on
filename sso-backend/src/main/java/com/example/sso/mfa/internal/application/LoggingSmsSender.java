package com.example.sso.mfa.internal.application;

import com.example.sso.mfa.SmsSender;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback {@link SmsSender} for environments without a real SMS gateway: it writes the code to the
 * application log (the MailHog-for-SMS equivalent) so the OTP flow works end to end in development.
 * Registered as the default only when no other {@code SmsSender} bean exists (see {@code MfaSmsConfig}), so
 * defining a real gateway bean replaces it outright.
 *
 * <p>Logging a live OTP defeats it as a possession factor, so the code is written to the log ONLY when
 * {@code sso.sms-otp.log-code} is enabled (true in the dev default, false in {@code application-prod.yml});
 * otherwise it logs a code-free notice, so a production deployment that forgets to wire a gateway degrades to
 * "not delivered" rather than leaking the secret.
 */
class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    private final boolean logCode;

    LoggingSmsSender(boolean logCode) {
        this.logCode = logCode;
    }

    @Override
    public void send(UUID orgId, String phoneNumber, String message) {
        if (logCode) {
            log.info("[dev-sms] to={} org={} :: {}", phoneNumber, orgId, message);
        } else {
            log.warn("SMS not delivered for org={}: no SMS gateway configured (the one-time code is withheld "
                    + "from the log). Wire a real SmsSender bean.", orgId);
        }
    }
}
