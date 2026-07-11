/**
 * Named interface for authentication-policy administration and evaluation: the admin facade, the
 * resolver/evaluator ports used at login, the step/policy read models and the spec/update command DTOs.
 * The policy aggregate and its persistence stay module-internal.
 */
@NamedInterface("policy")
package com.example.sso.authpolicy.policy;

import org.springframework.modulith.NamedInterface;
