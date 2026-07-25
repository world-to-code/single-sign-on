package com.example.sso.user.deny;

import java.util.UUID;

/**
 * The authorized author of a deny — their user id and their single APEX role at authoring time. Both are
 * stamped onto the deny row ({@code created_by}, {@code writer_apex_role_id}) so the lift guard can require a
 * lifter to be the author or STRICTLY dominate that apex — a peer cannot lift a peer's deny.
 */
public record DenyAuthor(UUID id, UUID apexRoleId) {
}
