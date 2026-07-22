package com.example.sso.mapping;

import java.util.UUID;

/**
 * What a mapping rule confers, addressed by id.
 *
 * <p>By id and never by name: {@code role} and {@code user_group} each allow a global row and an org row to
 * share a name, so resolving a name org-first has already produced one real privilege escalation here — a
 * benign local role cleared a ceiling that a privileged global role then collected.
 */
public record MappingTarget(MappingTargetKind kind, UUID targetId) {
}
