package com.example.sso.audit;

/**
 * The kind of object an audit event acts upon, so the admin log can be filtered to a scoped admin's
 * subtree. {@code NONE} is for events with no scopeable subject (e.g. a login), visible only to a super
 * admin or to the acting principal themselves.
 */
public enum AuditSubjectType {
    USER, GROUP, APPLICATION, RESOURCE, ORGANIZATION, NONE
}
