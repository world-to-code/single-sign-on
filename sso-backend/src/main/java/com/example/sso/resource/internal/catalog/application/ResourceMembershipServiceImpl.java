package com.example.sso.resource.internal.catalog.application;

import com.example.sso.resource.catalog.ResourceMembershipService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ResourceMembershipService}: USER membership without the current-actor authorization. It reuses
 * {@link ResourceAdminService}'s integrity-only member writes ({@code attachMemberChecked}/
 * {@code detachMemberChecked}) so the same-org / type / existence invariants have ONE implementation shared with
 * the admin path — never a second copy that could drift.
 */
@Service
@RequiredArgsConstructor
class ResourceMembershipServiceImpl implements ResourceMembershipService {

    private final ResourceAdminService admin;
    private final ResourceRepository resources;

    @Override
    @Transactional
    public void addUser(UUID resourceId, UUID userId) {
        admin.attachMemberChecked(resourceId, MemberType.USER, userId.toString());
    }

    @Override
    @Transactional
    public void removeUser(UUID resourceId, UUID userId) {
        admin.detachMemberChecked(resourceId, MemberType.USER, userId.toString());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> nameOf(UUID resourceId) {
        return resources.findById(resourceId).map(Resource::getName);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> orgIdOf(UUID resourceId) {
        return resources.findById(resourceId).map(Resource::getOrgId);
    }
}
