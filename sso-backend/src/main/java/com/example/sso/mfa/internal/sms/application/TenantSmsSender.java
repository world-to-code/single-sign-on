package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import com.example.sso.audit.AuditActor;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.SmsSender;
import com.example.sso.tenancy.OrgContext;
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
    private final OrgContext orgContext;
    private final AuditService audit;
    private final Map<SmsProvider, SmsGateway> gateways = new EnumMap<>(SmsProvider.class);
    private final SmsSender fallback;

    TenantSmsSender(SmsSettingsService settings, OrgContext orgContext, AuditService audit,
            List<SmsGateway> gateways, @Qualifier("loggingSmsSender") SmsSender fallback) {
        this.settings = settings;
        this.orgContext = orgContext;
        this.audit = audit;
        gateways.forEach(gateway -> this.gateways.put(gateway.provider(), gateway));
        this.fallback = fallback;
    }

    @Override
    public void send(UUID orgId, String phoneNumber, String message) {
        SmsAccount account = accountFor(orgId);
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
        deliver(gateway, account, phoneNumber, message, orgId);
    }

    /**
     * Sends, retrying ONCE and only when the provider was demonstrably never reached.
     *
     * <p>Deliberately not a general retry. A text is billed per message and is not idempotent, so re-sending
     * one that may already have been accepted charges the tenant twice and delivers twice. A refusal is worse
     * to retry: the same request is refused identically, so it buys nothing and doubles the noise. And the
     * person is waiting at a code prompt with a resend button, which is a better retry than any loop here.
     */
    private void deliver(SmsGateway gateway, SmsAccount account, String phoneNumber, String message, UUID orgId) {
        try {
            gateway.send(account, phoneNumber, message);
        } catch (SmsDeliveryException firstAttempt) {
            if (!firstAttempt.retryable()) {
                throw audited(firstAttempt, orgId);
            }
            log.warn("SMS provider {} was not reached; retrying once", firstAttempt.provider());
            try {
                gateway.send(account, phoneNumber, message);
            } catch (SmsDeliveryException retry) {
                throw audited(retry, orgId);
            }
        }
    }

    /**
     * Records the failure and hands it back. Auditing here rather than at the caller is what makes an
     * undelivered code visible at all: the send runs off the request thread, so nothing it throws reaches a
     * user, and a stack trace in a log is not something an administrator goes looking for.
     *
     * <p>The record carries the provider and its error CODE, never its message: that text is a third party's
     * and would land verbatim in the audit row.
     */
    private SmsDeliveryException audited(SmsDeliveryException failure, UUID orgId) {
        log.error("SMS not delivered via {}: {} ({})", failure.provider(), failure.providerCode(),
                failure.providerDetail() == null ? "no explanation given" : failure.providerDetail());
        audit.record(new AuditRecord(AuditType.SMS_SEND_FAILED, AuditActor.of(), false,
                "SMS not delivered via " + failure.provider(), null, AuditSubjectType.NONE, null, orgId,
                failure.providerCode(), true));
        return failure;
    }

    /**
     * The tenant's sending account, with the tenant BOUND on the connection rather than merely passed as an
     * argument.
     *
     * <p>{@code sms_settings} is under FORCE row-level security, so an unbound caller does not just fail to
     * match the tenant's row — it cannot SEE it. A code is sent from an {@code @Async} thread, which carries no
     * context, so without this the lookup came back empty and the send took the development fallback that
     * writes the code to a log instead of texting it. A configured tenant looked exactly like an unconfigured
     * one.
     *
     * <p>A {@code null} org is the platform account, whose row is readable with no context by design.
     */
    private SmsAccount accountFor(UUID orgId) {
        if (orgId == null) {
            return settings.resolve(null).orElse(null);
        }
        return orgContext.callInOrg(orgId, () -> settings.resolve(orgId)).orElse(null);
    }
}
