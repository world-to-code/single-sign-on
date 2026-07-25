package com.example.sso.user.deny;

/**
 * What a deny attaches to. USER is the most specific tier; ROLE and GROUP are the middle tier (a "principal");
 * ORG is the least specific. The subject id is a user / role / group / organization id respectively.
 */
public enum DenySubjectKind {
    USER,
    ROLE,
    GROUP,
    ORG
}
