package com.example.sso.audit.internal.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the sealer on a timer, because audit writes must not wait for it.
 *
 * <p>A failure here leaves rows unsealed rather than losing them — the next pass picks them up from where this
 * one stopped. It is logged rather than swallowed: a sealer that has been failing quietly for a week is a
 * chain that ends a week ago, and the gap only becomes visible when somebody needs it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AuditChainSealSweeper {

    private final AuditChainSealer sealer;

    @Scheduled(fixedDelayString = "${sso.audit.chain.seal-interval}")
    void seal() {
        try {
            int sealed = sealer.seal();
            if (sealed > 0) {
                log.debug("Sealed {} audit row(s) into the hash chain", sealed);
            }
        } catch (RuntimeException e) {
            log.error("Audit chain sealing failed; rows stay unsealed until the next pass", e);
        }
    }
}
