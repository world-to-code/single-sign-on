package com.example.sso.admin;

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
}
