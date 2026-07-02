package com.example.sso.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link SessionMetadataStore}: the in-memory per-session device store behind the
 * self-service "My Profile" sessions list. Pure state store — asserts on returned metadata rather than
 * verifying interactions. Focus: per-user scoping of handles, newest-first ordering, and the rekey
 * contract that must preserve the public handle + creation time across {@code changeSessionId()}.
 */
class SessionMetadataStoreTest {

    private SessionMetadataStore store;

    @BeforeEach
    void setUp() {
        store = new SessionMetadataStore();
    }

    @Test
    void recordThenLookupByHandleReturnsTheSameSession() {
        store.record("sid-1", "alice", "UA", "10.0.0.1");
        SessionMetadata recorded = store.forUser("alice").get(0);

        Optional<SessionMetadata> found = store.findByUserAndHandle("alice", recorded.handle());

        assertThat(found).isPresent();
        assertThat(found.get().sessionId()).isEqualTo("sid-1");
        assertThat(found.get().ip()).isEqualTo("10.0.0.1");
    }

    @Test
    void forUserReturnsOnlyThatUsersSessionsNewestFirst() throws InterruptedException {
        store.record("sid-1", "alice", "UA", "ip");
        Thread.sleep(2);
        store.record("sid-2", "alice", "UA", "ip");
        store.record("sid-3", "bob", "UA", "ip");

        List<SessionMetadata> alice = store.forUser("alice");

        assertThat(alice).extracting(SessionMetadata::sessionId).containsExactly("sid-2", "sid-1");
    }

    @Test
    void handlesAreScopedPerUserSoOneUserCannotResolveAnothersHandle() {
        store.record("sid-1", "alice", "UA", "ip");
        String aliceHandle = store.forUser("alice").get(0).handle();

        assertThat(store.findByUserAndHandle("bob", aliceHandle)).isEmpty();
    }

    @Test
    void rekeyPreservesHandleAndCreationTimeUnderTheNewSessionId() {
        store.record("old-id", "alice", "UA", "ip");
        SessionMetadata before = store.forUser("alice").get(0);

        store.rekey("old-id", "new-id");

        List<SessionMetadata> after = store.forUser("alice");
        assertThat(after).hasSize(1);
        assertThat(after.get(0).sessionId()).isEqualTo("new-id");
        assertThat(after.get(0).handle()).isEqualTo(before.handle());
        assertThat(after.get(0).createdAt()).isEqualTo(before.createdAt());
        assertThat(store.findByUserAndHandle("alice", before.handle())).isPresent();
    }

    @Test
    void rekeyToTheSameIdIsANoOpButStillRemovesNothing() {
        store.record("sid-1", "alice", "UA", "ip");
        String handle = store.forUser("alice").get(0).handle();

        store.rekey("sid-1", "sid-1");

        // The entry is removed then not re-added when ids are equal (rekey guards oldId.equals(newId)).
        assertThat(store.findByUserAndHandle("alice", handle)).isEmpty();
    }

    @Test
    void rekeyOfUnknownSessionDoesNothing() {
        store.rekey("ghost", "new");

        assertThat(store.forUser("alice")).isEmpty();
    }

    @Test
    void removeForgetsTheSession() {
        store.record("sid-1", "alice", "UA", "ip");

        store.remove("sid-1");

        assertThat(store.forUser("alice")).isEmpty();
    }

    @Test
    void touchAdvancesLastSeenWithoutChangingCreatedAt() throws InterruptedException {
        store.record("sid-1", "alice", "UA", "ip");
        SessionMetadata metadata = store.forUser("alice").get(0);
        Thread.sleep(2);

        store.touch("sid-1");

        assertThat(metadata.lastSeenAt()).isAfterOrEqualTo(metadata.createdAt());
    }

    @Test
    void touchOfUnknownSessionIsSilentlyIgnored() {
        store.touch("ghost"); // no exception

        assertThat(store.forUser("alice")).isEmpty();
    }
}
