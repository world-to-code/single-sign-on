package com.example.sso.user.deny;

import java.util.UUID;

/**
 * A request to author one deny: withhold {@code pattern} (a permission name or a {@code <resource>:*} wildcard)
 * from {@code subjectId} of {@code kind}. The command the admin controller maps its validated request into.
 */
public record DenySpec(DenySubjectKind kind, UUID subjectId, String pattern) {
}
