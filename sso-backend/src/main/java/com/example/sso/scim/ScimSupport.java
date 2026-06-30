package com.example.sso.scim;

import de.captaingoldfish.scim.sdk.common.exceptions.BadRequestException;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Shared SCIM helpers: id parsing (malformed -> 400) and SCIM list paging. */
final class ScimSupport {

    private ScimSupport() {
    }

    /** Malformed input is a 400 BadRequest (not 404); a valid-but-absent id is 404 at lookup. */
    static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid id: " + id);
        }
    }

    /**
     * Maps SCIM's 1-based arbitrary {@code startIndex} + count to an offset-addressed page, so any
     * (non-page-aligned) start index returns the exact window — not a divided page number.
     */
    static Pageable pageable(long startIndex, int count) {
        return OffsetPageable.fromStartIndex(startIndex, Math.max(1, count));
    }
}
