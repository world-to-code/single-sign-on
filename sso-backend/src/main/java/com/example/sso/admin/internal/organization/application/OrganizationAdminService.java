package com.example.sso.admin.internal.organization.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.organization.OrganizationView;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Presentation adapter for the organization admin API: delegates to the {@link OrganizationService}
 * (which already projects to {@link OrganizationView}) and records an audit trail per mutation.
 */
@Service
@RequiredArgsConstructor
public class OrganizationAdminService {

    private final OrganizationService organizations;
    private final AdminAuditLogger audit;

    public Page<OrganizationView> list(int page, int size) {
        return Page.of(organizations.listAll(), page, size);
    }

    public OrganizationView get(UUID id) {
        return organizations.findView(id).orElseThrow(() -> new NotFoundException("organization not found"));
    }

    public OrganizationView create(NewOrganization command) {
        OrganizationView view = organizations.create(command);
        audit.log(AuditType.ORGANIZATION_CREATED, AuditSubjectType.ORGANIZATION, view.id().toString(), view.slug());
        return view;
    }

    public OrganizationView update(UUID id, String name, OrganizationStatus status) {
        OrganizationView view = organizations.update(id, name, status);
        audit.log(AuditType.ORGANIZATION_UPDATED, AuditSubjectType.ORGANIZATION, id.toString(), status.name());
        return view;
    }

    public void delete(UUID id) {
        organizations.delete(id);
        audit.log(AuditType.ORGANIZATION_DELETED, AuditSubjectType.ORGANIZATION, id.toString(), null);
    }

    public void addMember(UUID id, UUID userId) {
        organizations.addMember(id, userId);
        audit.log(AuditType.ORGANIZATION_MEMBER_ADDED, AuditSubjectType.ORGANIZATION, id.toString(), userId.toString());
    }

    public void removeMember(UUID id, UUID userId) {
        organizations.removeMember(id, userId);
        audit.log(AuditType.ORGANIZATION_MEMBER_REMOVED, AuditSubjectType.ORGANIZATION, id.toString(), userId.toString());
    }
}
