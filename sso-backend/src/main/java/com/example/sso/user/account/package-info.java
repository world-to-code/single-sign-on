/**
 * Named interface for user-account identity: the {@link UserService} facade, the {@link UserAccount} read
 * model, the create/update commands, lockout policy, login-resolution scope and the account lifecycle events
 * ({@code UserDeletedEvent}, {@code UserAccessChangedEvent}). Account persistence stays module-internal.
 */
@NamedInterface("account")
package com.example.sso.user.account;

import org.springframework.modulith.NamedInterface;
