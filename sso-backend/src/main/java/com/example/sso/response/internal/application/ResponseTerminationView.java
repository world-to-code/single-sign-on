package com.example.sso.response.internal.application;

/**
 * How many sessions the call ended. Reported rather than a bare 204 because zero and three mean different
 * things to whoever is deciding what to do next — an account with no live session was not signed in.
 */
public record ResponseTerminationView(int sessions) {
}
