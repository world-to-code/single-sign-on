package com.example.sso.user;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Create/update request for an organizational group. externalId is the optional source id for a
 * future LDAP/SCIM sync; memberUserIds is the wholesale membership for the group.
 */
public record GroupRequest(@NotBlank String name, String description, String externalId,
                           List<String> memberUserIds) {
}
