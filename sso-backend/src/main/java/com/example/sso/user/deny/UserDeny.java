package com.example.sso.user.deny;

import java.util.UUID;

/** A USER-level deny row — its id (to lift it) and the withheld {@code pattern} — for the console to list. */
public record UserDeny(UUID id, String pattern) {
}
