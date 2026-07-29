package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import com.example.sso.mfa.SmsSender;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Routes an outbound message through the ACTING TENANT's own gateway account, falling back to the platform
 * account and finally to the deployment's default sender.
 *
 * <p>The analogue of {@code TenantMailSender}, and it exists for the same reason: a customer company's
 * one-time codes should arrive from that company's sending number and be billed to its own provider account,
 * not the platform's.
 *
 * <p>A configured tenant whose send FAILS is not quietly downgraded to the fallback. The fallback writes the
 * code to a log in development and deliberately withholds it in production, so silently taking it would turn
 * "the gateway rejected our sender number" into "the code was never delivered and nobody was told" — the
 * failure has to reach the caller, which fails the sign-in loudly.
 */
@Component
@Primary
@Slf4j
class TenantSmsSender implements SmsSender {

    private final SmsSettingsService settings;
    private final Map<SmsProvider, SmsGateway> gateways = new EnumMap<>(SmsProvider.class);
    private final SmsSender fallback;

    TenantSmsSender(SmsSettingsService settings, List<SmsGateway> gateways,
            @Qualifier("loggingSmsSender") SmsSender fallback) {
        this.settings = settings;
        gateways.forEach(gateway -> this.gateways.put(gateway.provider(), gateway));
        this.fallback = fallback;
    }

    @Override
    public void send(UUID orgId, String phoneNumber, String message) {
        SmsAccount account = settings.resolve(orgId).orElse(null);
        if (account == null) {
            fallback.send(orgId, phoneNumber, message);
            return;
        }
        SmsGateway gateway = gateways.get(account.provider());
        if (gateway == null) {
            // A provider stored by a build that had a client for it, running on one that does not. Refusing is
            // the only honest answer: the tenant configured a gateway and this deployment cannot use it.
            throw new IllegalStateException("no SMS client for provider " + account.provider());
        }
        gateway.send(account, phoneNumber, message);
    }
}
