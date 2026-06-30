package com.example.sso.scim;

/** Request to issue a SCIM bearer token; ttlDays null = no expiry. */
public record IssueScimTokenRequest(String description, Long ttlDays) {
}
