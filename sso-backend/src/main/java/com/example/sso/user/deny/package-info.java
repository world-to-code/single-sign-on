/**
 * Named interface for NEGATIVE permissions (deny): the {@link DenyService} that authors and lifts denies, the
 * {@link DenySpec} command, the {@link DenySubjectKind} the deny attaches to, the {@link DenyRow} row the
 * console lists against a user, the {@link DenyAuthority} port the admin module implements to authorize
 * authoring/lifting, and the {@link LastAdminInvariant} port it implements to refuse a deny that would strip a
 * tier's last admin. Deny persistence stays module-internal.
 *
 * <p>{@link LastAdminInvariant} is about DENIES only. The sibling question — may an ABAC retraction take a
 * tier's last admin — is declared by its own caller as {@code mapping.LastAdminRetractionGuard}, because no
 * {@code user} code asks it and a deny consumer should not have to see it.
 */
@NamedInterface("deny")
package com.example.sso.user.deny;

import org.springframework.modulith.NamedInterface;
