package com.example.sso.email.template;

/**
 * A mail somebody was waiting for did not go out.
 *
 * <p>An event rather than a call back into the module that asked for the mail: that module already publishes
 * {@link EmailRequested} to get here, so answering it directly would close a cycle. It also keeps the email
 * module ignorant of WHY anyone wanted the mail — it reports the fact and nothing else.
 *
 * @param deliveryKey the attempt that was waiting, as given on the request
 */
public record EmailDeliveryFailed(String deliveryKey) {
}
