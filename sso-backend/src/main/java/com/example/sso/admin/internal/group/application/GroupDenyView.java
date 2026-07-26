package com.example.sso.admin.internal.group.application;

import com.example.sso.user.deny.DenyRow;
import java.util.List;

/**
 * A group's deny management state for the console: the {@code candidates} an admin may withhold (the permissions
 * the group's delegated roles hand to its members) and the {@code denies} already authored on the group (each
 * liftable by id). Kept off the shared {@code GroupView} — this is admin-console-only.
 */
public record GroupDenyView(List<String> candidates, List<DenyRow> denies) {
}
