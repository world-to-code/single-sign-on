package com.example.sso.portal;

/** A launchable application surfaced in the user portal / admin. */
public record ApplicationView(String id, String type, String name, String launchUrl) {
}
