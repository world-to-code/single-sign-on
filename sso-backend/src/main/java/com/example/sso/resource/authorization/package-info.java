/**
 * Named interface exposing the per-principal authorization ports (can-view / can-manage / list-scoping for
 * users, groups, applications and resources) that the admin module composes into its instance-level checks.
 * The resource DAG itself and its admin API stay module-internal.
 */
@NamedInterface("authorization")
package com.example.sso.resource.authorization;

import org.springframework.modulith.NamedInterface;
