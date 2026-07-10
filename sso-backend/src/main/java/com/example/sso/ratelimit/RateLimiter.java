package com.example.sso.ratelimit;

/**
 * A distributed token-bucket rate limiter. Each key holds a bucket of at most {@code capacity} tokens that
 * refills continuously; a call spends one token. Capacity is therefore the BOUNDED burst — a client may
 * spend a whole bucket at once, but never more, and thereafter proceeds at the refill rate.
 *
 * <p>A fixed window would instead admit two full windows' worth of calls back-to-back across the boundary
 * (2x the intended rate), and per-node memory would hand a client a fresh allowance on every node. The
 * implementation stays module-internal.
 */
public interface RateLimiter {

    /** Spends one token for {@code key}; false when the bucket is empty (the caller is over its rate). */
    boolean tryAcquire(String key);
}
