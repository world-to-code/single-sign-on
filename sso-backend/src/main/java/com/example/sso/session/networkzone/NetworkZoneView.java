package com.example.sso.session.networkzone;

import java.util.List;

/** Admin view of a named network zone. */
public record NetworkZoneView(String id, String name, String description, List<String> cidrs) {
}
