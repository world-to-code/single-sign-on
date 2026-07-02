package com.example.sso.admin.internal.user.api;

import com.example.sso.user.UserUpdate;
import jakarta.validation.constraints.Email;
import java.util.Set;

/** Admin request to update a user's profile, enabled flag, and role assignment. */
public record UpdateUserRequest(String displayName, @Email String email, boolean enabled, Set<String> roles) {

    /** The update command. */
    public UserUpdate toUpdate() {
        return new UserUpdate(displayName, email, enabled, roles);
    }
}
