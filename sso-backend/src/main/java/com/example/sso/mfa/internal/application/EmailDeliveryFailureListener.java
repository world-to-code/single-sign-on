package com.example.sso.mfa.internal.application;

import com.example.sso.email.template.EmailDeliveryFailed;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records that a code sent by email never left, so the person waiting for it can be told on their next
 * request rather than being told the code they eventually guess is wrong.
 *
 * <p>The mail itself is sent by the email module, which is asked through an event and answers through one —
 * this is that answer. Mail nobody is waiting on carries no key and is ignored here.
 */
@Component
@RequiredArgsConstructor
class EmailDeliveryFailureListener {

    private final CodeDeliveryStatus deliveryStatus;

    @EventListener
    public void onEmailDeliveryFailed(EmailDeliveryFailed event) {
        deliveryStatus.markFailed(event.deliveryKey());
    }
}
