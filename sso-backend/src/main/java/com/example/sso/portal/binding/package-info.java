/**
 * Named interface for policy-binding resolution: given a user and an application (or portal), the effective
 * authentication and session policy from the {@code policy_binding} matrix. Consumers apply their own
 * login/step-up/session fallback to the returned {@code Optional}. The binding store stays module-internal.
 */
@NamedInterface("binding")
package com.example.sso.portal.binding;

import org.springframework.modulith.NamedInterface;
