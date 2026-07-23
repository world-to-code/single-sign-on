package com.example.sso.user.internal.rbac.domain;

/** The subject a {@link PrincipalPermissionDeny} attaches to — a GROUP or a ROLE, referenced by id. */
public enum DenySubjectType {
    GROUP,
    ROLE
}
