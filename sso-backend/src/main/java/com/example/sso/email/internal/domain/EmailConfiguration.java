package com.example.sso.email.internal.domain;

import com.example.sso.email.EmailProvider;

/**
 * Everything a {@link SmtpSettings} row holds, as one value — secrets already ENCRYPTED.
 *
 * <p>Exists so the entity is assigned in one move instead of from eight positional arguments, half of them
 * adjacent Strings. A swapped pair there compiles and then sends a tenant's mail as somebody else, or with the
 * wrong credential.
 */
public record EmailConfiguration(EmailProvider provider, String host, Integer port, String username,
                                 String passwordEncrypted, String apiKeyEncrypted, String fromAddress,
                                 boolean starttls) {
}
