package com.example.sso.scim;

/** The plaintext SCIM token, returned only once at issuance. */
public record ScimTokenIssued(String token, String description) {
}
