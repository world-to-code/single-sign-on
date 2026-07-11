/**
 * Named interface for per-user application access: the readiness result and query, the assignment views and
 * admin-console access port, plus the request DTOs and the servlet filter that gates SP access on granted
 * step-up factors. Assignment/policy persistence stays module-internal.
 */
@NamedInterface("access")
package com.example.sso.portal.access;

import org.springframework.modulith.NamedInterface;
