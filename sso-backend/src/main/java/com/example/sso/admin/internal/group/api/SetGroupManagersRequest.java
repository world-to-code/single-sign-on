package com.example.sso.admin.internal.group.api;

import java.util.List;

/** Replaces the managers (scoped admins) of a group. */
public record SetGroupManagersRequest(List<String> managerUserIds) {
}
