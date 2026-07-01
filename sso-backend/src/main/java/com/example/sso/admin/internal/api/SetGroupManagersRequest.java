package com.example.sso.admin.internal.api;

import java.util.List;

/** Replaces the managers (scoped admins) of a group. */
public record SetGroupManagersRequest(List<String> managerUserIds) {
}
