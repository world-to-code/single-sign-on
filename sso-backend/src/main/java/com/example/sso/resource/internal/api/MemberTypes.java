package com.example.sso.resource.internal.api;

import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.shared.error.BadRequestException;
import java.util.Locale;

/** Parses client-supplied member-kind names into {@link MemberType}, mapping unknowns to a 400. */
final class MemberTypes {

    static MemberType parse(String name) {
        try {
            return MemberType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BadRequestException.of("resource.memberType.unknown", name);
        }
    }

    private MemberTypes() {
    }
}
