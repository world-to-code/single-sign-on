/**
 * Named interface for the authentication-factor vocabulary: the {@link AuthFactor} enum and the
 * {@link Factors} constants that the auth, session and admin modules reference when composing login steps.
 * The most widely consumed slice of this module, deliberately kept minimal.
 */
@NamedInterface("factor")
package com.example.sso.authpolicy.factor;

import org.springframework.modulith.NamedInterface;
