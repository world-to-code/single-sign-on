package com.example.sso.user.deny;

import java.util.UUID;

/** One deny row on a subject — its id (to lift it) and the withheld {@code pattern} — for the console to list. */
public record DenyRow(UUID id, String pattern) {
}
