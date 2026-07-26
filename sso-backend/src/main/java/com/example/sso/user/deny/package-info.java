/**
 * Named interface for NEGATIVE permissions (deny): the {@link DenyService} that authors and lifts denies, the
 * {@link DenySpec} command, the {@link DenySubjectKind} the deny attaches to, the {@link UserDeny} row the
 * console lists against a user, the {@link DenyAuthority} port the admin module implements to authorize
 * authoring/lifting, and the {@link LastAdminInvariant} port it implements to refuse a deny that would strip a
 * tier's last admin. Deny persistence stays module-internal.
 */
@NamedInterface("deny")
package com.example.sso.user.deny;

import org.springframework.modulith.NamedInterface;
