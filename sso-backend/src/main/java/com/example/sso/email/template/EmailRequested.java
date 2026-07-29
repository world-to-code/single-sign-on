package com.example.sso.email.template;

import java.util.Map;
import java.util.UUID;

/**
 * A request to send a branded email as a side effect of a business action. A business module (mfa, …) publishes
 * it instead of composing and sending inline; the email module consumes it off-thread and owns the "how" —
 * resolving the tenant's template ({@link EmailComposer}) and routing its relay ({@code TenantMailSender}).
 *
 * <p>{@code deliveryKey} identifies the login attempt waiting for this mail, when there is one, so a send that
 * fails AFTER the screen has been told "we emailed you" can still be reported on that person's next request.
 *
 * <p>{@code orgId} is carried on the payload because the listener runs off the request thread (no ambient
 * {@code OrgContext}); a {@code null} orgId sends via the default relay/template (e.g. a global account, or a
 * self-service signup before the org exists). Publishing is instant, so the publisher neither blocks on SMTP
 * nor makes a send measurably slower than a no-op.
 */
public record EmailRequested(EmailEvent kind, String recipient, Map<String, Object> variables, UUID orgId,
                             String deliveryKey) {

    /** For mail that nobody is waiting on a screen for — an invitation, a notification. */
    public EmailRequested(EmailEvent kind, String recipient, Map<String, Object> variables, UUID orgId) {
        this(kind, recipient, variables, orgId, null);
    }
}
