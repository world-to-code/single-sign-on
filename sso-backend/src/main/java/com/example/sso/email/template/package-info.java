/**
 * Named interface for the email-template vocabulary: the {@link EmailEvent} slots, the {@link OutboundEmail}
 * rendered message, and the {@link EmailComposer} the auth/mfa and onboarding modules call to turn an event
 * plus its variables into a branded, ready-to-send message.
 */
@NamedInterface("template")
package com.example.sso.email.template;

import org.springframework.modulith.NamedInterface;
