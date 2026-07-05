package com.example.sso.organization;

/** Lifecycle state of an organization (tenant). A SUSPENDED org rejects login at the entry point. */
public enum OrganizationStatus {
    ACTIVE,
    SUSPENDED
}
