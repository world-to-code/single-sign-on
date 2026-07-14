package com.example.sso.logoutretry;

/**
 * The single decision point a logout-propagation pass calls after settling every participant it could reach:
 * given whether any participant is still undelivered, either schedule the next durable retry (jittered
 * exponential backoff), or — once the give-up cap is reached — run the caller's {@code onGiveUp} and stop.
 * Centralizing the cap here keeps "revocation propagates, and giving up is bounded and observable" in one place.
 */
public interface LogoutRetryCoordinator {

    /**
     * Decide the fate of a termination that a propagation pass just processed.
     *
     * @param queue        the driver's retry queue key
     * @param sid          the terminated session id
     * @param username     the subject, carried forward so a later sweep re-drive and the give-up audit have it
     * @param hasRemaining whether the index still holds undelivered participants after this pass
     * @param onGiveUp     run once, only when the give-up cap is reached with participants still remaining
     *                     (the caller audits the abandoned participants and clears the index here)
     */
    void reschedule(String queue, String sid, String username, boolean hasRemaining, Runnable onGiveUp);
}
