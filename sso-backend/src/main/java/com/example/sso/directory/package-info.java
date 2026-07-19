/**
 * Inbound directory synchronisation: a tenant PULLS profile attributes from its own directory (LDAP/LDAPS now;
 * Google Workspace and Entra ID behind the same connector shape later).
 *
 * <p>Deliberately narrow. SCIM already covers the push direction and federation JIT covers account creation, so
 * this only fills attributes on accounts that already exist — which leaves exactly one owner for account
 * lifecycle and stops a mis-configured connector mass-creating accounts in a tenant.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Directory sync")
package com.example.sso.directory;
