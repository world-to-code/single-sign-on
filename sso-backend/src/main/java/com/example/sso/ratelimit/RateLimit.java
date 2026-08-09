package com.example.sso.ratelimit;

/**
 * One named token bucket, shared across every node.
 *
 * <p>Capacity is the BOUNDED BURST: a caller may spend a whole bucket at once and never more, then proceeds
 * at the refill rate. That is the property a fixed window does not have — a window admits two full windows'
 * worth back to back across its boundary, which is twice the rate nobody asked for.
 */
public interface RateLimit {

    /** Spends one token for {@code key}; false when the bucket is empty and the caller is over its rate. */
    boolean tryAcquire(String key);
}
