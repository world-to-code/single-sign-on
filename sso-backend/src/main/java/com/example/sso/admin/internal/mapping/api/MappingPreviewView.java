package com.example.sso.admin.internal.mapping.api;

import java.util.List;

/**
 * Dry-run result for a mapping rule: how many users the predicate currently matches, and a capped sample of
 * them (id + username) for the admin to eyeball who would be affected before saving.
 */
public record MappingPreviewView(int matchedCount, List<MatchedUser> sample) {
}
