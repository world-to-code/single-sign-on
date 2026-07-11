/**
 * Named interface for permission-based access control: the {@link RbacService} facade that resolves effective
 * permissions, the {@code Permissions} constants and the {@code PermissionGrantPolicy} that governs which
 * permissions an actor may grant. Permission persistence stays module-internal.
 */
@NamedInterface("rbac")
package com.example.sso.user.rbac;


import org.springframework.modulith.NamedInterface;
