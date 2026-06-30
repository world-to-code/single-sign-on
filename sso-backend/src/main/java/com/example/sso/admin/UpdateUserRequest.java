package com.example.sso.admin;

import jakarta.validation.constraints.Email;

import java.util.Set;

/** Admin request to update a user's profile, enabled flag, and role assignment. */
public record UpdateUserRequest(String displayName, @Email String email, boolean enabled, Set<String> roles) {
}
