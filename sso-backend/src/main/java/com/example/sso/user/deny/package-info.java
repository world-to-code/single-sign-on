/**
 * Named interface for NEGATIVE permissions (deny): the {@link DenyService} that authors and lifts denies, the
 * {@link DenySpec} command, the {@link DenySubjectKind} the deny attaches to, and the {@link DenyAuthority}
 * port the admin module implements to authorize authoring and lifting. Deny persistence stays module-internal.
 */
@NamedInterface("deny")
package com.example.sso.user.deny;

import org.springframework.modulith.NamedInterface;
