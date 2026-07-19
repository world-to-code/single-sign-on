package com.example.sso.directory.internal.application;

import java.util.List;
import java.util.Map;

/**
 * One person as the directory described them, normalised away from any particular protocol: the stable
 * identifier we correlate on, plus the raw attribute values that were asked for. Keeping this shape
 * protocol-neutral is what lets Google Workspace and Entra ID reuse the sync engine unchanged.
 */
public record DirectoryEntry(String externalId, Map<String, List<String>> attributes) {
}
