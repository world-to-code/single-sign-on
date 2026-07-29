package com.example.sso.session.lifecycle;

import java.io.Serializable;

/**
 * The part of a session's device metadata that has to be STORED — everything else about a session
 * ({@code createdAt}, {@code lastSeenAt}, the id itself) the session already knows.
 *
 * <p>Held as an attribute of the Redis session, so it is visible to every node and cannot outlive the session
 * it describes. {@link Serializable} because Spring Session serializes attributes with the JDK serializer.
 *
 * <p>Package-private on purpose: it is how {@link SessionMetadataStore} persists, not something callers pass
 * around. They see {@link SessionMetadata}.
 *
 * @param handle stable opaque public id for the session, so the real session id never leaves the server. Minted
 *               once and carried across {@code changeSessionId()} rotation with the rest of the attributes.
 */
record SessionDevice(String handle, String userAgent, String ip) implements Serializable {

    /**
     * Pinned rather than compiler-derived: these live in Redis across a deployment, and a derived id changes
     * whenever the shape does — which would make every session written by the previous build unreadable, not
     * just its device metadata.
     */
    private static final long serialVersionUID = 1L;
}
