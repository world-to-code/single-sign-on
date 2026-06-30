package com.example.sso.admin;

/** Result of client registration; clientSecret is shown once and null for public clients. */
public record ClientCreated(String clientId, String clientSecret) {
}
