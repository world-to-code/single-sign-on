package com.example.sso.ratelimit;

import java.time.Duration;

/**
 * Mints a rate limit with its OWN policy, for callers whose rate is not the login rate.
 *
 * <p>Public because the second consumer arrived: the machine response API needs a much smaller allowance over
 * a much longer window than a login form does, and the alternative was a second Bucket4j configuration
 * somewhere else. Two places configuring one library is how two limiters end up disagreeing about what a
 * bucket means.
 *
 * <p>{@code namespace} keeps one consumer's keys off another's — the keys are caller-supplied, so without it
 * a client id and a username could collide in Redis and spend each other's tokens.
 */
public interface RateLimits {

    RateLimit named(String namespace, long capacity, Duration window);
}
