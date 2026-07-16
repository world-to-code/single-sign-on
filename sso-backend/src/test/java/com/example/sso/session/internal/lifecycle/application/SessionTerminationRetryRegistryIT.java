package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable session-termination registry against a real Redis: due-time gating, the request round-trip
 * (org/username/userId/attempts), the visibility-timeout lease, removal and reschedule-in-place — the
 * guarantees the scheduled sweep relies on to re-drive lost terminations across nodes and restarts. A single
 * shared queue backs every entry, so each case uses a unique target and asserts against its OWN key.
 */
class SessionTerminationRetryRegistryIT extends AbstractIntegrationTest {

    @Autowired
    SessionTerminationRetryRegistry registry;

    private SessionTerminationRequest uniqueUserRequest() {
        return SessionTerminationRequest.forUser("user-" + UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void aScheduledRequestIsDueOnlyAtOrAfterItsTime() {
        SessionTerminationRequest request = uniqueUserRequest();
        registry.schedule(request, 1, 1_000L);

        assertThat(registry.due(999L, 1000)).doesNotContain(request.key());
        assertThat(registry.due(1_000L, 1000)).contains(request.key());

        registry.remove(request.key());
    }

    @Test
    void aUsernameRequestRoundTripsItsFieldsAndAttempts() {
        UUID org = UUID.randomUUID();
        SessionTerminationRequest request = SessionTerminationRequest.forUser("carol", org);
        registry.schedule(request, 3, 1_000L);

        SessionTerminationRetryRegistry.Pending pending = registry.pending(request.key()).orElseThrow();
        assertThat(pending.attempts()).isEqualTo(3);
        assertThat(pending.request()).isEqualTo(request);
        assertThat(pending.request().username()).isEqualTo("carol");
        assertThat(pending.request().orgId()).isEqualTo(org);
        assertThat(pending.request().userId()).isNull();

        registry.remove(request.key());
    }

    @Test
    void aMemberRequestRoundTripsTheUserIdAndAGlobalNullOrg() {
        UUID userId = UUID.randomUUID();
        SessionTerminationRequest request = new SessionTerminationRequest(null, null, userId); // null org (global)
        registry.schedule(request, 1, 1_000L);

        SessionTerminationRequest read = registry.pending(request.key()).orElseThrow().request();
        assertThat(read).isEqualTo(request);
        assertThat(read.orgId()).isNull();
        assertThat(read.username()).isNull();
        assertThat(read.userId()).isEqualTo(userId);

        registry.remove(request.key());
    }

    @Test
    void pendingIsEmptyForAnUnknownKey() {
        assertThat(registry.pending("nope:" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void leasingPushesTheEntryOutOfTheDueWindow() {
        SessionTerminationRequest request = uniqueUserRequest();
        registry.schedule(request, 1, 1_000L);
        registry.lease(request.key(), 60_000L); // a re-drive claimed it

        assertThat(registry.due(5_000L, 1000)).doesNotContain(request.key());   // no longer due at 5s
        assertThat(registry.due(60_000L, 1000)).contains(request.key());        // re-surfaces after the lease

        registry.remove(request.key());
    }

    @Test
    void removeDropsBothTheQueueEntryAndTheMeta() {
        SessionTerminationRequest request = uniqueUserRequest();
        registry.schedule(request, 1, 1_000L);

        registry.remove(request.key());

        assertThat(registry.due(5_000L, 1000)).doesNotContain(request.key());
        assertThat(registry.pending(request.key())).isEmpty();
    }

    @Test
    void reschedulingMovesTheDueTimeAndAttemptInPlace() {
        SessionTerminationRequest request = uniqueUserRequest();
        registry.schedule(request, 1, 1_000L);
        registry.schedule(request, 2, 100_000L);

        assertThat(registry.due(5_000L, 1000)).doesNotContain(request.key());     // old time no longer due
        assertThat(registry.due(100_000L, 1000)).contains(request.key());
        assertThat(registry.pending(request.key()).orElseThrow().attempts()).isEqualTo(2);

        registry.remove(request.key());
    }
}
