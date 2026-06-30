package com.example.sso.portal;

import java.util.List;

/** Whether a user may launch an app now, or must complete additional (step-up) factors first. */
public record AppAccess(boolean ready, List<String> pendingFactors) {
}
