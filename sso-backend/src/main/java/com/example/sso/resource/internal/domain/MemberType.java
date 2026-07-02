package com.example.sso.resource.internal.domain;

/**
 * What a resource may contain. {@code RESOURCE} constrains whether child resources may be attached
 * (via {@code resource_edge}); the other kinds are polymorphic leaf members ({@code resource_member}).
 */
public enum MemberType {
    RESOURCE, GROUP, APPLICATION, USER
}
