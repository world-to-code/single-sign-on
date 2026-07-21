package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.ActingAdminTier;
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
import java.util.List;
import java.util.Map;
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
            UUID profileId = profileToBind(command, org);

            // Validated BEFORE creating: a rejected attribute must not leave a half-made account behind.
            if (profileId != null) {
                validator.validate(profileId, command.attributeValues());
            }

            UserAccount user = userService.createUser(command.user(), org, command.challenge());

            if (profileId != null) {
                userService.assignProfile(user.getId(), profileId);
                writeAttributes(user.getId(), command.attributeValues());
            }
            // Membership as well as the home org_id, so every isMember-based check sees them.
            if (org != null) {
                organizations.addMember(org, user.getId());
            }
            // A console password is temporary; the user must replace it on first login. An import has none.
            if (command.user().rawPassword() != null) {
                userService.requirePasswordReset(user.getId());
            }

            return audited(user, command);
        } catch (IllegalArgumentException domainRefusal) {
            throw new ConflictException(domainRefusal.getMessage());
        }
    }

    /**
     * The profile this account is bound to, or null when there is none to bind.
     *
     * <p>Null happens for the platform tier — an un-drilled super admin creating a global account, which no
     * tenant profile governs. Otherwise the caller's choice wins over the organization's default: an import
     * picked a profile and downloaded its template, so falling back would discard every column it was told to
     * provide.
     */
    private UUID profileToBind(NewUserCommand command, UUID org) {
        if (org == null) {
            return null;
        }
        return command.profileId() != null ? command.profileId() : validator.defaultForCreation();
    }

    /**
     * Stores the attributes the profile declares, skipping the blanks.
     *
     * <p>A blank arrives from a form that submitted an empty field rather than omitting it. Storing it would
     * create a row asserting the user HAS that attribute with no value, which an ABAC condition then matches
     * on — so absence and emptiness must not be recorded the same way.
     */
    private void writeAttributes(UUID userId, Map<String, List<String>> attributeValues) {
        for (Map.Entry<String, List<String>> attribute : attributeValues.entrySet()) {
            for (String value : attribute.getValue()) {
                if (value != null && !value.isBlank()) {
                    attributes.add(EntityKind.USER, userId.toString(), attribute.getKey(), value);
                }
            }
        }
    }

    /** The view to return, and the trail that says who was made — the keys of the account, never its values. */
    private AdminUserView audited(UserAccount user, NewUserCommand command) {
        AdminUserView created = AdminUserView.of(user);
        auditLogger.log(AuditType.USER_CREATED, AuditSubjectType.USER, created.id(),
                "username=" + created.username() + " roles=" + command.user().roleNames());
        return created;
    }
}
