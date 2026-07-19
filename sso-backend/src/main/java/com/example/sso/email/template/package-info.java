/**
 * Named interface for the email-template vocabulary: the {@link EmailEvent} slots, the {@link OutboundEmail}
 * rendered message, the {@link EmailComposer} the auth/mfa and onboarding modules call to turn an event plus
 * its variables into a branded, ready-to-send message, and the {@link EmailRequested} event a business module
 * publishes to have the email module compose and send that message off-thread.
 */
@NamedInterface("template")
package com.example.sso.email.template;

import org.springframework.modulith.NamedInterface;
