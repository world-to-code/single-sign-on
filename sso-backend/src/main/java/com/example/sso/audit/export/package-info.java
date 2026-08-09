/**
 * Named interface for shipping the audit trail to an external collector (SIEM): where it goes, and the
 * credential it authenticates with. The store and the validation stay module-internal.
 */
@NamedInterface("export")
package com.example.sso.audit.export;

import org.springframework.modulith.NamedInterface;
