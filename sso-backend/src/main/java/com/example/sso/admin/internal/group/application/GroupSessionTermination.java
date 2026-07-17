package com.example.sso.admin.internal.group.application;

/**
 * Summary of a group-wide session termination: how many members' sessions were ended, the total sessions
 * ended across them, and how many members were skipped because the caller may not revoke them (e.g. an
 * administrator member, for a scoped delegate).
 */
public record GroupSessionTermination(int users, int sessions, int skipped) {
}
