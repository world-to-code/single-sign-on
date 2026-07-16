/**
 * Public catalog surface of the resource module: a programmatic membership port ({@link ResourceMembershipService})
 * and the resource-lifecycle event ({@link ResourceDeletedEvent}). Consumed by other modules (e.g. auto-mapping)
 * that add users to a resource or clean up when a resource is deleted — WITHOUT the current-actor authorization
 * the internal admin API enforces (the caller has already authorized the operation its own way).
 */
@NamedInterface("catalog")
package com.example.sso.resource.catalog;

import org.springframework.modulith.NamedInterface;
