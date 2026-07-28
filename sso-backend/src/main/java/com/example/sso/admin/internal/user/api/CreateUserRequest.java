package com.example.sso.admin.internal.user.api;

import com.example.sso.user.account.NewUser;
import com.example.sso.user.role.Roles;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Admin request to create a user; roles default to ROLE_USER when omitted. */
public record CreateUserRequest(@NotBlank String username,
                                @NotBlank @Email String email,
                                String displayName,
                                @NotBlank @Size(min = 8) String password,
                                Set<String> roles,
                                /* Attribute values for the profile a new user is created on; validated
                                   server-side against what that profile declares. */
                                Map<String, List<String>> attributes,
                                /* The profile to create this person on; null takes the organization's
                                   default. Sent explicitly so the values below are validated against the
                                   SAME profile the form rendered, not whichever one happens to be default
                                   when the request lands. */
                                UUID profileId) {

    /** The create command, defaulting the role set to {@link Roles#USER} when none is given. */
    /** The declared attribute values, never null. */
    public Map<String, List<String>> attributeValues() {
        return attributes == null ? Map.of() : attributes;
    }

    public NewUser toNewUser() {
        Set<String> roleNames = (roles == null || roles.isEmpty()) ? Set.of(Roles.USER) : roles;
        return new NewUser(username, email, displayName, password, roleNames);
    }
}
