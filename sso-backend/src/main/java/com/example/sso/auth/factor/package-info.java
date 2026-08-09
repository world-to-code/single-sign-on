/**
 * Named interface for what an account can PROVE: the second-factor availability a caller outside {@code auth}
 * needs before deciding whether demanding one is a challenge or a lockout.
 */
@NamedInterface("factor")
package com.example.sso.auth.factor;

import org.springframework.modulith.NamedInterface;
