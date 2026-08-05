package com.example.sso.branding.internal.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops cached branding once the write that changed it has COMMITTED.
 *
 * <p>AFTER_COMMIT is the whole point of routing this through an event. Evicting inline would leave a window
 * in which a concurrent read misses, loads the row as it stands BEFORE the commit, and re-caches the value
 * the write was replacing — a cache that is wrong until its TTL, which is worse than one that was never
 * evicted at all.
 *
 * <p>A failure here cannot roll the write back: the invalidation itself swallows Redis errors, and an exception
 * thrown from an AFTER_COMMIT listener is swallowed by the publisher anyway. The write is already durable;
 * the worst case is a stale entry until the TTL.
 */
@Component
@RequiredArgsConstructor
class BrandingCacheEvictor {

    private final BrandingCache cache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onBrandingChanged(BrandingChanged event) {
        cache.invalidate();
    }
}
