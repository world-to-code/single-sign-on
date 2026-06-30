package com.example.sso.web;

import java.util.List;

/** Immutable view of the currently authenticated principal. */
public record CurrentUser(String username, List<String> authorities) {
}
