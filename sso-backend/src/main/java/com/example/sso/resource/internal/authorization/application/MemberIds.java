package com.example.sso.resource.internal.authorization.application;

import com.example.sso.shared.error.BadRequestException;
import java.util.UUID;

/** Parses a GROUP/USER member id, mapping a malformed value to a 400 (not a 500) at the boundary. */
public final class MemberIds {

    public static UUID requireUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw BadRequestException.of("resource.memberId.notUuid");
        }
    }

    private MemberIds() {
    }
}
