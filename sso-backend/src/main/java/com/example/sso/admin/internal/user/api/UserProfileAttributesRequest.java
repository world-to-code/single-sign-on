package com.example.sso.admin.internal.user.api;

import java.util.List;
import java.util.Map;

/**
 * The whole set of profile columns a save carries: key to its values.
 *
 * <p>A column the map omits is CLEARED, which is how the form says "unset this optional attribute" — a merge
 * would have no way to express it. It also means the request must carry the full set, which is precisely what
 * lets the server ask whether a REQUIRED column is still filled.
 */
public record UserProfileAttributesRequest(Map<String, List<String>> values) {

    public Map<String, List<String>> values() {
        return values == null ? Map.of() : values;
    }
}
