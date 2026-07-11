package com.example.sso.admin.internal.user.api;

import com.example.sso.user.account.NewUser;
import com.example.sso.user.role.Roles;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** Admin request to create a user; roles default to ROLE_USER when omitted. */
public record CreateUserRequest(@NotBlank String username,
                                @NotBlank @Email String email,
                                String displayName,
                                @NotBlank @Size(min = 8) String password,
                                Set<String> roles) {

    /** The create command, defaulting the role set to {@link Roles#USER} when none is given. */
    public NewUser toNewUser() {
        Set<String> roleNames = (roles == null || roles.isEmpty()) ? Set.of(Roles.USER) : roles;
        return new NewUser(username, email, displayName, password, roleNames);
    }
}
