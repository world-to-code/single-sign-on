package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembling one account: validate against its profile, create it, bind the profile, write the attributes,
 * record the organization membership, and say so in the audit log.
 *
 * <p>Its own service because that sequence is a single reason to change, and it was the only part of the
 * admin user surface that needed the profile validator, the attribute store and the organization service —
 * three collaborators that existed on the larger class for one method. Every route that makes an account goes
 * through here, so the rules cannot hold on the console path and not on the import path.
 */
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private final UserService userService;
    private final ProfileAttributeValidator validator;
    private final AttributeService attributes;
    private final OrganizationService organizations;
    private final ActingAdminTier tier;
    private final AdminAuditLogger auditLogger;

    @Transactional
    public AdminUserView create(NewUserCommand command) {
        try {
            UUID org = tier.actingOrg();
            // Validate BEFORE creating: a required attribute missing should not leave a half-made account
            // behind, and the profile is what makes those declarations mean anything at all.
            UUID profileId = org == null ? null
                    : (command.profileId() != null ? command.profileId() : validator.defaultForCreation());
            if (profileId != null) {
                validator.validate(profileId, command.attributeValues());
            }
            UserAccount user = userService.createUser(command.user(), org, command.challenge());
            if (profileId != null) {
                userService.assignProfile(user.getId(), profileId);
                command.attributeValues().forEach((key, values) -> values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .forEach(value -> attributes.add(EntityKind.USER, user.getId().toString(), key, value)));
            }
            // Record the org membership too (SCIM and self-signup already do): a user carries a home org_id AND
            // is a member of that org, so every isMember-based check (e.g. resource-admin delegation) sees it.
            if (org != null) {
                organizations.addMember(org, user.getId());
            }
            // An admin-console user is created with a TEMPORARY password the admin chose; require the user to
            // set their own on first login (login completion refuses to finalize until they do).
            if (command.user().rawPassword() != null) {
                userService.requirePasswordReset(user.getId());
            }
            AdminUserView created = AdminUserView.of(user);
            auditLogger.log(AuditType.USER_CREATED, AuditSubjectType.USER, created.id(),
                    "username=" + created.username() + " roles=" + command.user().roleNames());
            return created;
        } catch (IllegalArgumentException e) {
            throw new ConflictException(e.getMessage());
        }
    }
}
