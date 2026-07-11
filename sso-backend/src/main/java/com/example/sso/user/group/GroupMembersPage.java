package com.example.sso.user.group;

import com.example.sso.user.account.Suggestion;

import java.util.List;

/** A page of a group's members. items carry (id, username) for display. */
public record GroupMembersPage(long total, int page, int size, List<Suggestion> items) {
}
