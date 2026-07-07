package com.example.sso.organization.internal.application;

import com.example.sso.customer.CustomerService;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationAccessRevokedEvent;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.organization.OrganizationView;
import com.example.sso.organization.internal.domain.Organization;
import com.example.sso.organization.internal.domain.OrganizationMembership;
import com.example.sso.organization.internal.domain.OrganizationMembershipRepository;
import com.example.sso.organization.internal.domain.OrganizationRepository;
import com.example.sso.shared.Slug;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.UserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default {@link OrganizationService}: the tenant registry and global-user↔org membership. */
@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {

    private final OrganizationRepository organizations;
    private final OrganizationMembershipRepository memberships;
    private final ApplicationEventPublisher events;
    private final UserService users;
    private final OrgContext orgContext;
    private final CustomerService customers;

    @Override
    @Transactional
    public OrganizationView create(NewOrganization command) {
        String slug = Slug.normalize(command.slug());
        // Every organization is a branch of a customer (고객사): the requested one (if it exists and is ACTIVE)
        // or the default customer, keeping the FK non-null. Slugs are unique PER CUSTOMER, so resolve the parent
        // first, then reject a collision only within that customer's namespace.
        UUID customerId = resolveCustomer(command.customerId());
        if (organizations.existsByCustomerIdAndSlug(customerId, slug)) {
            throw new ConflictException("organization slug '" + slug + "' already exists");
        }
        Organization org = new Organization(slug, requireName(command.name()), command.profile());
        org.assignCustomer(customerId);
        return view(organizations.save(org));
    }

    @Override
    @Transactional
    public OrganizationView update(UUID id, String name, OrganizationStatus status) {
        Organization org = require(id);
        org.rename(requireName(name));
        org.changeStatus(status);
        if (status == OrganizationStatus.SUSPENDED) {
            // Suspending an org must END its members' live sessions bound to it, not merely block new logins
            // (the login-flow status gate). Fan out the same access-revoked event the session module already
            // terminates on — one per member, resolved in the platform context (RLS-crossing read).
            orgContext.callAsPlatform(() -> memberships.findUserIdsByOrgId(id))
                    .forEach(userId -> events.publishEvent(new OrganizationAccessRevokedEvent(id, userId)));
        }
        return view(org);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        organizations.delete(require(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationView> findView(UUID id) {
        return organizations.findById(id).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationView> listAll() {
        return organizations.findAll().stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationRef> findBySlug(String slug) {
        // The single-label {org}.base host and tenant-first login resolve within the DEFAULT customer's
        // namespace, keeping that legacy path unambiguous now that slugs are unique only per customer.
        return organizations.findByCustomerIdAndSlug(customers.defaultCustomer().getId(), Slug.normalize(slug))
                .map(o -> o);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationRef> findBranch(UUID customerId, String slug) {
        return organizations.findByCustomerIdAndSlug(customerId, Slug.normalize(slug)).map(o -> o);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> customerIdOf(UUID orgId) {
        return organizations.findById(orgId).map(Organization::getCustomerId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isBranchOf(UUID orgId, Set<UUID> customerIds) {
        return !customerIds.isEmpty() && organizations.existsByIdAndCustomerIdIn(orgId, customerIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> branchIdsForCustomers(Set<UUID> customerIds) {
        return customerIds.isEmpty() ? Set.of() : organizations.findIdsByCustomerIdIn(customerIds);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMember(UUID orgId, UUID userId) {
        // Bind the org so the RLS-guarded membership read is scoped to it.
        return orgContext.callInOrg(orgId, () -> memberships.existsByOrgIdAndUserId(orgId, userId));
    }

    @Override
    @Transactional
    public void addMember(UUID orgId, UUID userId) {
        orgContext.runInOrg(orgId, () -> {
            require(orgId); // reject membership in an unknown org
            if (users.findById(userId).isEmpty()) { // reject an unknown user (else a bare FK violation -> 500)
                throw new NotFoundException("user not found");
            }
            if (!memberships.existsByOrgIdAndUserId(orgId, userId)) {
                // saveAndFlush: force the INSERT to run INSIDE this runInOrg scope (GUC = orgId) so RLS WITH
                // CHECK passes. A plain save() defers to commit — after the scope restores the outer context.
                memberships.saveAndFlush(new OrganizationMembership(orgId, userId));
            }
        });
    }

    @Override
    @Transactional
    public void removeMember(UUID orgId, UUID userId) {
        orgContext.runInOrg(orgId, () -> {
            // Only act (and publish) when a membership actually existed — a no-op delete must not trigger the
            // downstream session-termination the event drives.
            if (memberships.existsByOrgIdAndUserId(orgId, userId)) {
                memberships.deleteByOrgIdAndUserId(orgId, userId);
                // Flush the DELETE INSIDE this runInOrg scope (GUC = orgId): the derived delete otherwise defers
                // to tx commit, by which point runInOrg has restored the outer context and RLS USING no longer
                // matches the row (0 rows -> StaleStateException). Mirrors addMember's saveAndFlush.
                memberships.flush();
                events.publishEvent(new OrganizationAccessRevokedEvent(orgId, userId));
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> orgIdsForUser(UUID userId) {
        // Cross-org query (all orgs a user belongs to) — must run in the platform context.
        return orgContext.callAsPlatform(() -> Set.copyOf(memberships.findOrgIdsByUserId(userId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> memberIds(UUID orgId) {
        // Cross-org read (the org's whole member set) — runs as platform so it is exact regardless of the
        // caller's bound context; the caller's authority to enumerate this org is enforced upstream.
        return orgContext.callAsPlatform(() -> Set.copyOf(memberships.findUserIdsByOrgId(orgId)));
    }

    @Override
    @Transactional(readOnly = true)
    public long memberCount(UUID orgId) {
        // Counts one org's members by explicit org_id; runs as platform so the count is exact regardless of
        // the caller's bound context (the caller's authorization to see this org is enforced upstream).
        return orgContext.callAsPlatform(() -> memberships.countByOrgId(orgId));
    }

    private Organization require(UUID id) {
        return organizations.findById(id).orElseThrow(() -> new NotFoundException("organization not found"));
    }

    private OrganizationView view(Organization org) {
        return OrganizationView.of(org, org.getCreatedAt(), org.getCompanyProfile());
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("organization name is required");
        }
        return name.trim();
    }

    // Resolve the parent customer for a new branch: the requested one (which must exist and be ACTIVE — no
    // creating branches under an unknown or suspended customer), or the default customer when none is given.
    private UUID resolveCustomer(UUID requested) {
        if (requested == null) {
            return customers.defaultCustomer().getId();
        }
        if (!customers.isActive(requested)) {
            throw new BadRequestException("customer not found or not active");
        }
        return requested;
    }
}
