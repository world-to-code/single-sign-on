package com.example.sso.organization;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The organization (tenant) registry and global-user↔org membership. Users are global identities
 * (Auth0-Organizations model); a membership links a user to many orgs, and login resolves the active
 * org. The backing entities never leave the module — callers consume {@link OrganizationRef}/
 * {@link OrganizationView} and plain ids.
 */
public interface OrganizationService {

    /** Creates an organization; slug is normalized to lowercase and must be unique. */
    OrganizationView create(NewOrganization command);

    /** Renames and/or changes the status of an organization. */
    OrganizationView update(UUID id, String name, OrganizationStatus status);

    /** Deletes an organization and (via FK cascade) its memberships. */
    void delete(UUID id);

    /** Admin single-org read. */
    Optional<OrganizationView> findView(UUID id);

    List<OrganizationView> listAll();

    /** Entry-point lookup by slug (login resolves the active org before identity). */
    Optional<OrganizationRef> findBySlug(String slug);

    /** Resolve a branch (organization) by slug WITHIN a parent customer — the {@code {branch}.{customer}}
     *  host lookup, so branches under different customers may share a slug. */
    Optional<OrganizationRef> findBranch(UUID customerId, String slug);

    /** Whether the user is a member of the organization. */
    boolean isMember(UUID orgId, UUID userId);

    /** Adds the user to the organization (idempotent). */
    void addMember(UUID orgId, UUID userId);

    /** Removes the user from the organization; publishes {@link OrganizationAccessRevokedEvent}. */
    void removeMember(UUID orgId, UUID userId);

    /** The ids of every organization the user belongs to. */
    Set<UUID> orgIdsForUser(UUID userId);

    /** The ids of every user that belongs to the organization (for scoping tenant-bound listings, e.g. SCIM). */
    Set<UUID> memberIds(UUID orgId);

    /** The number of members in an organization (analytics). */
    long memberCount(UUID orgId);
}
