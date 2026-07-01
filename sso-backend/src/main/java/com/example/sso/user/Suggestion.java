package com.example.sso.user;

/** A typeahead search result: an id plus its human label (username or group name). */
public record Suggestion(String id, String label) {
}
