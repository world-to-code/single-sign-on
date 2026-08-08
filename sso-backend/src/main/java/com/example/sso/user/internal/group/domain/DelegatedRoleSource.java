package com.example.sso.user.internal.group.domain;

import java.util.UUID;

/**
 * Which group delegated which role to a member.
 *
 * <p>The login path asks only for the role ids and rightly discards this — it is building an authority set,
 * not an explanation. But an administrator looking at a permission needs the group NAME to know where to go:
 * a role delegated by a group is removed from the group, and revoking it on the user does nothing at all.
 */
public interface DelegatedRoleSource {

    UUID getGroupId();

    String getGroupName();

    UUID getRoleId();
}
