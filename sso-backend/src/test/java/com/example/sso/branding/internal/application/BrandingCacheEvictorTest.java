package com.example.sso.branding.internal.application;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PHASE is the guarantee, and it was pinned by nothing.
 *
 * <p>Invalidating before commit leaves a window where a concurrent read misses, loads the row as it stands
 * BEFORE the write, and re-caches the value the write was replacing — wrong until the TTL, which is worse
 * than never having invalidated. Switching this listener to BEFORE_COMMIT, or to a plain {@code @EventListener},
 * reopens exactly that and leaves every other test green.
 */
class BrandingCacheEvictorTest {

    @Test
    void theInvalidationRunsAfterTheWriteHasCommitted() throws Exception {
        TransactionalEventListener listener = BrandingCacheEvictor.class
                .getDeclaredMethod("onBrandingChanged", BrandingChanged.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(listener).as("the invalidation must be bound to the transaction").isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
