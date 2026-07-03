package com.example.sso.session;

import java.util.List;

/** Create/update command for a named network zone: a label, optional description, and its CIDR ranges. */
public record NetworkZoneSpec(String name, String description, List<String> cidrs) {
}
