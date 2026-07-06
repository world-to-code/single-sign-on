package com.example.sso.resource.internal.domain;

import java.io.Serializable;
import java.util.UUID;

/** Composite identifier for {@link ResourceTypeAllowedMember} — (type, member kind). */
public record ResourceTypeAllowedMemberId(UUID typeId, MemberType memberType) implements Serializable {
}
