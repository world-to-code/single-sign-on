/**
 * Metadata-driven auto-mapping. A {@code mapping_rule} assigns the users carrying a metadata attribute
 * ({@code key = value}) to a target (currently a group), re-evaluated when a user's attributes change. Rules are
 * org-scoped and apply only within the authoring admin's grant authority. Consumes the {@code metadata},
 * {@code user} (group membership) and {@code audit} modules through their public APIs.
 */
@ApplicationModule
package com.example.sso.mapping;

import org.springframework.modulith.ApplicationModule;
