/**
 * Entity metadata (key/value tags) — the ABAC foundation. Stores org-scoped attributes on users, groups,
 * applications and resources through {@link com.example.sso.metadata.AttributeService}; other modules attach
 * their own admin surfaces to edit them and (later) target policies / auto-mapping rules by attribute.
 */
@ApplicationModule
package com.example.sso.metadata;

import org.springframework.modulith.ApplicationModule;
