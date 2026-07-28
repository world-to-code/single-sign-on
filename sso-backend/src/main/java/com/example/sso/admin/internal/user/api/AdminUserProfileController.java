package com.example.sso.admin.internal.user.api;

import com.example.sso.admin.internal.shared.security.CanChangeUserProfile;
import com.example.sso.admin.internal.shared.security.CanViewUser;
import com.example.sso.admin.internal.user.application.UserProfileAttributeService;
import com.example.sso.admin.internal.user.application.UserProfileService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.Audited;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.shared.security.RequireStepUp;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's PROFILE, as the user detail page reads and writes it: the columns their profile declares, and the
 * profile itself. Split from {@link AdminUserController} because these four endpoints are the only users of
 * three of its collaborators — the profile service, the profile-attribute service and the attribute store —
 * while every other endpoint there uses none of them. The test for these routes was already mocking four
 * services it never called, which is what that shape does to the people who come after it.
 *
 * <p>Same URL space and the same layered gates as its sibling: URL access needs a completed MFA login plus a
 * fresh admin-console elevation token, each method carries its permission, and every write is
 * {@link CanChangeUserProfile} — {@code user:update}, reach, AND the administrator-target refusal, because a
 * profile write deletes attributes that can be conditions on mapping rules and policy bindings.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserProfileController {

    private final UserProfileService userProfiles;
    private final UserProfileAttributeService profileAttributes;
    private final AttributeService metadata;

    /**
     * What moving this user onto {@code profileId} would delete, and whether it can happen at all.
     *
     * <p>Gated as the WRITE, not as a read: it enumerates the attribute keys the person carries outside the
     * chosen profile, so under a read gate a delegate who may not move an administrator could still iterate
     * the org's profiles and read that administrator's whole key set — and, since a blocked key can now mean
     * "a deny you cannot lift rides on this", learn about denies on principals they cannot see.
     */
    @GetMapping("/{id}/profile/preview")
    @CanChangeUserProfile
    public ProfileSwitchPreviewView previewProfileSwitch(@PathVariable UUID id, @RequestParam UUID profileId) {
        return ProfileSwitchPreviewView.of(userProfiles.preview(id, profileId));
    }

    /**
     * Moves the user onto another profile. Destructive by design — a profile decides which attributes a person
     * HAS, so anything the target does not declare stops existing — which is why the preview above is separate
     * and why this carries a step-up: those keys can be conditions on mapping rules and policy bindings, so
     * deleting one can retract a role.
     */
    // Audited here for the REFUSALS, which is the half a service-layer trail cannot cover: ProfileSwitchAuditor
    // is AFTER_COMMIT, so a 403 on an administrator target, a 409 on a stale preview or a blocked key leaves no
    // row at all without this. The two rows on success are deliberate and differ in kind — this one says the
    // request happened and how it ended, the listener's says what it deleted.
    @PutMapping("/{id}/profile")
    @CanChangeUserProfile
    @RequireStepUp
    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.USER, subjectParam = "id")
    public UserProfileAttributesView switchProfile(@PathVariable UUID id,
                                                   @Valid @RequestBody SwitchProfileRequest request) {
        userProfiles.switchTo(id, request.profileId(), request.confirmedKeys());
        return columnsOf(id);
    }

    /**
     * The columns this user's profile declares — what the console renders as the person's own fields.
     *
     * <p>A plain READ, unlike the preview above: a read-only administrator must be able to open a user's
     * detail page. The preview is gated as a write because it enumerates keys OUTSIDE the chosen profile, one
     * profile at a time; this returns the person's own declared columns, which is what {@code user:read} is
     * for.
     */
    @GetMapping("/{id}/profile-attributes")
    @CanViewUser
    public UserProfileAttributesView profileAttributes(@PathVariable UUID id) {
        return columnsOf(id);
    }

    /**
     * Replaces this user's profile columns as one set. A per-key write cannot tell an attribute the profile
     * REQUIRES and nobody filled from one the request merely omits, so this is the shape that can enforce the
     * declaration — the same validator the create form answers to.
     */
    @PutMapping("/{id}/profile-attributes")
    @CanChangeUserProfile
    @RequireStepUp
    // subject + subjectParam, not a bare type: AuditScope resolves a USER subject by parsing subjectId as a
    // UUID, so a row left at subject NONE is invisible to every scoped delegate reviewing this person.
    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.USER, subjectParam = "id")
    public UserProfileAttributesView replaceProfileAttributes(@PathVariable UUID id,
                                                              @Valid @RequestBody UserProfileAttributesRequest request) {
        profileAttributes.replace(id, request.values());
        return columnsOf(id);
    }

    /**
     * The view both writes answer with, assembled directly rather than by calling the GET handler.
     *
     * <p>A handler self-invoking another handler ties this response's shape to that method's signature and
     * bypasses its {@code @PreAuthorize} proxy — harmless here, since the caller has already cleared the
     * strictly stronger write gate, but it is a coupling with nothing to gain.
     */
    private UserProfileAttributesView columnsOf(UUID id) {
        return UserProfileAttributesView.of(profileAttributes.columnsOf(id),
                metadata.attributesOf(EntityKind.USER, id.toString()));
    }
}
