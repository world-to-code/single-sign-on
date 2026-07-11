/**
 * Named interface for session-policy administration: the {@link SessionPolicyService} facade with its
 * details/view read models and the request/spec/update command DTOs. Policy persistence and its cached
 * projection stay module-internal.
 */
@NamedInterface("policy")
package com.example.sso.session.policy;

import org.springframework.modulith.NamedInterface;
