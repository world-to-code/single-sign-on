package com.example.sso.user.internal.domain;

import java.util.UUID;

/** (roleId, member) projection row for resolving members of many roles in one query. */
public record RoleMemberRow(UUID roleId, AppUser member) {
}
