package com.example.sso.user.group;

/**
 * One role a group delegates to its members, as the console needs it: the id to write back, the name to show.
 *
 * <p>Both, because the delegation is written BY ID. A console that round-tripped names would re-introduce the
 * resolution step that let a request be authorized against one role and bind another of the same name — a name
 * resolves org-first with a global fallback, and two partial unique indexes let both rows exist.
 */
public record GroupRole(String id, String name) {
}
