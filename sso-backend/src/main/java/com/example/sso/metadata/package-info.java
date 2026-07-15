/**
 * Entity metadata (key/value tags). Stores org-scoped attributes on users, groups, applications and resources
 * through {@link AttributeService}; other modules attach their own admin surfaces to read and edit them, and
 * to look entities up by attribute.
 */
@ApplicationModule
package com.example.sso.metadata;

import org.springframework.modulith.ApplicationModule;
