package com.example.sso.logoutretry;

/**
 * A protocol's hook into the durable logout-retry sweep. Each logout protocol (OIDC back-channel, SAML SLO)
 * contributes one driver: it names the Redis queue it schedules retries on, and knows how to re-drive a
 * still-undelivered session termination. The sweeper enumerates all drivers, so adding a protocol is adding
 * a driver — the generic retry machinery ({@code internal}) never changes.
 */
public interface LogoutRetryDriver {

    /** The retry queue key this driver owns (e.g. {@code oidc:bcl:retry}); the sweeper reads due sids from it. */
    String queue();

    /**
     * Re-attempt propagation for a session whose earlier fan-out left undelivered participants. Re-reads the
     * (now-shrunk) index and re-schedules or gives up via the {@link LogoutRetryCoordinator}. Must dispatch the
     * actual delivery off the sweeper thread (through the protocol's {@code @Async} proxy), so the sweep lock is
     * held only briefly.
     */
    void redrive(String sid, String username);
}
