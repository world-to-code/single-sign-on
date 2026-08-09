package com.example.sso.audit;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The catalog of audit event kinds. Each value declares its {@link AuditCategory}, so classification is
 * table-driven (no string matching) and the admin log can be browsed by category. Recorders reference
 * these values instead of hardcoding type strings; the persisted {@code type} is the enum name.
 */
@Getter
@RequiredArgsConstructor
public enum AuditType {

    // Authentication
    AUTH_ORGANIZATION(AuditCategory.AUTHENTICATION),
    AUTH_IDENTIFY(AuditCategory.AUTHENTICATION),
    AUTH_SUCCESS(AuditCategory.AUTHENTICATION),
    AUTH_FAILURE(AuditCategory.AUTHENTICATION),
    MFA_SUCCESS(AuditCategory.AUTHENTICATION),
    MFA_FAILURE(AuditCategory.AUTHENTICATION),
    MFA_LOCKED(AuditCategory.AUTHENTICATION),
    REAUTH_SUCCESS(AuditCategory.AUTHENTICATION),
    REAUTH_FAILURE(AuditCategory.AUTHENTICATION),

    // User self-service actions
    TOTP_ENROLLED(AuditCategory.USER_ACTION),
    TOTP_REMOVED(AuditCategory.USER_ACTION),

    // Session lifecycle
    SESSION_CREATED(AuditCategory.SESSION),
    SESSION_REVOKED(AuditCategory.SESSION),
    SESSION_EXPIRED_IDLE(AuditCategory.SESSION),
    SESSION_EXPIRED_ABSOLUTE(AuditCategory.SESSION),
    SESSION_CONCURRENT_EXPIRED(AuditCategory.SESSION),
    SESSION_CONTEXT_MISMATCH(AuditCategory.SESSION),
    LOGOUT(AuditCategory.SESSION),
    OIDC_BACKCHANNEL_LOGOUT(AuditCategory.SESSION),
    SAML_SLO(AuditCategory.SESSION),
    SESSION_ADMIN_REVOKED(AuditCategory.SESSION),
    SESSION_TERMINATION_DEFERRED(AuditCategory.SESSION), // in-thread termination retries exhausted; handed to the durable sweep to re-drive
    SESSION_TERMINATION_FAILED(AuditCategory.SESSION), // durable retries exhausted too — the session may outlive the access change until its TTL
    /**
     * The OAuth2 grants a revoked account had handed to applications were taken back — the half of revocation
     * that session termination does not reach, because a relying party refreshes without passing through
     * {@code /oauth2/authorize}. Recorded as a FAILURE when it could not be done: the account is then signed
     * out everywhere while its applications keep minting tokens, which is the state nobody would notice.
     */
    OIDC_AUTHORIZATION_REVOKED(AuditCategory.SESSION),

    // Network / rate access
    IP_BLOCKED(AuditCategory.ACCESS),
    ADMIN_IP_BLOCKED(AuditCategory.ACCESS),
    ADMIN_ELEVATION_DENIED(AuditCategory.ACCESS),
    /**
     * A call to the machine response API was turned away — a credential this IdP did not issue for this host,
     * or a well-formed one with no correlation id. Recorded because an API that can end any session being
     * probed is itself the signal, and the reason code tells a rejected token apart from a caller's bug.
     */
    RESPONSE_ACTION_REFUSED(AuditCategory.ACCESS),
    /** A response client burned its whole action budget — a runaway detector, or one acting on a bad signal. */
    RESPONSE_BUDGET_EXHAUSTED(AuditCategory.ACCESS),
    RATE_LIMITED(AuditCategory.ACCESS),

    // Application sign-on
    SAML_SSO_ISSUED(AuditCategory.APP_ACCESS),
    SAML_STEPUP_REQUIRED(AuditCategory.APP_ACCESS),
    OIDC_APP_ACCESS(AuditCategory.APP_ACCESS),

    // Access-control decisions and administration
    AUTHORIZATION_DENIED(AuditCategory.AUTHORIZATION),
    USER_PERMISSIONS_UPDATED(AuditCategory.AUTHORIZATION),
    /** A time-bounded role grant reached its expiry and was swept away — nobody decided this today. */
    ROLE_GRANT_EXPIRED(AuditCategory.AUTHORIZATION),
    PERMISSION_DENY_CREATED(AuditCategory.AUTHORIZATION),
    PERMISSION_DENY_LIFTED(AuditCategory.AUTHORIZATION),
    // A membership drop removed the subject a deny resolved against, so the withheld permission came back
    // without anyone clearing the lift ceiling. Only emitted where that ceiling cannot be asked (SCIM).
    PERMISSION_DENY_LIFTED_BY_SYNC(AuditCategory.AUTHORIZATION),
    ROLE_CREATED(AuditCategory.AUTHORIZATION),
    ROLE_UPDATED(AuditCategory.AUTHORIZATION),
    ROLE_DELETED(AuditCategory.AUTHORIZATION),
    GROUP_ROLES_UPDATED(AuditCategory.AUTHORIZATION),
    GROUP_MANAGERS_UPDATED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_CREATED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_UPDATED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_DELETED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_APPLIED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_RETRACTED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_RETRACTION_REFUSED(AuditCategory.AUTHORIZATION),  // rolled back: it would have left a tier admin-less
    MAPPING_RULE_RECONCILE_STALLED(AuditCategory.AUTHORIZATION),   // repeatedly unreconcilable — the sweep now defers it
    MAPPING_RULE_AUTHOR_UNAUTHORIZED(AuditCategory.AUTHORIZATION), // rule outlived its author's grant authority — grant skipped
    MAPPING_RULE_LEGACY_AUTHOR(AuditCategory.AUTHORIZATION),       // a grant by a rule with no recorded author (pre-V97/system)

    // Administrative operations on users
    USER_CREATED(AuditCategory.ADMIN),
    USER_UPDATED(AuditCategory.ADMIN),
    USER_ENABLED(AuditCategory.ADMIN),
    USER_DISABLED(AuditCategory.ADMIN),
    USER_DELETED(AuditCategory.ADMIN),
    USER_MFA_RESET(AuditCategory.ADMIN),
    /** A reversible hold was placed: sessions ended, and every sign-in must prove a second factor until it ends. */
    ACCOUNT_HELD(AuditCategory.ADMIN),
    /** Somebody ended the hold early. Distinct from the expiry below — one is a decision, the other is the clock. */
    ACCOUNT_HOLD_LIFTED(AuditCategory.ADMIN),
    ACCOUNT_HOLD_EXPIRED(AuditCategory.ADMIN),

    // Administrative operations on organizations (tenants)
    ORGANIZATION_CREATED(AuditCategory.ADMIN),
    ORGANIZATION_UPDATED(AuditCategory.ADMIN),
    ORGANIZATION_DELETED(AuditCategory.ADMIN),
    ORGANIZATION_MEMBER_ADDED(AuditCategory.ADMIN),
    ORGANIZATION_MEMBER_REMOVED(AuditCategory.ADMIN),
    ORGANIZATION_CONTEXT_ENTERED(AuditCategory.ADMIN),

    // Administrative configuration changes recorded by the @Audited interceptor (the request method+path
    // distinguishes create vs delete for the coarse *_CHANGED kinds)
    ATTRIBUTE_CHANGED(AuditCategory.ADMIN),
    // The attribute SCHEMA — declaring, redefining or removing a profile attribute definition. Distinct from
    // ATTRIBUTE_CHANGED, which is a VALUE written onto one entity; this is the catalog every entity's values
    // are shaped by, so an unlogged change to it is an unlogged change to what the directory may carry.
    ATTRIBUTE_DEFINITION_CHANGED(AuditCategory.ADMIN),
    /**
     * The audit export's destination was set or re-pointed. Recorded because it decides where every tenant's
     * security history goes — redirecting it is how somebody would arrange for their own actions to be
     * reviewed by nobody.
     */
    AUDIT_EXPORT_CONFIGURED(AuditCategory.ADMIN),
    AUTH_POLICY_CREATED(AuditCategory.AUTHORIZATION),
    AUTH_POLICY_UPDATED(AuditCategory.AUTHORIZATION),
    AUTH_POLICY_DELETED(AuditCategory.AUTHORIZATION),
    SESSION_POLICY_CREATED(AuditCategory.SESSION),
    SESSION_POLICY_UPDATED(AuditCategory.SESSION),
    SESSION_POLICY_DELETED(AuditCategory.SESSION),
    RESOURCE_CHANGED(AuditCategory.ADMIN),
    OIDC_CLIENT_CREATED(AuditCategory.ADMIN),
    OIDC_CLIENT_UPDATED(AuditCategory.ADMIN),
    OIDC_CLIENT_DELETED(AuditCategory.ADMIN),
    RELYING_PARTY_CREATED(AuditCategory.ADMIN),
    RELYING_PARTY_UPDATED(AuditCategory.ADMIN),
    RELYING_PARTY_DELETED(AuditCategory.ADMIN),
    // Whoever controls the mail relay or the SMS gateway controls where one-time codes, verification links and
    // password-reset links are DELIVERED — redirecting them is an account-takeover primitive that needs no
    // password. So a change to either is privilege-relevant and must be attributable to an administrator after
    // the fact, alongside the refused attempts the interceptor records as failures.
    // The provider refused or could not be reached, so a one-time code was not delivered. Recorded because the
    // send is off-request: nothing it throws reaches the user, and the tenant's own gateway config is the usual
    // cause — an unregistered sending number, an expired key — which is only actionable if somebody can see it.
    SMS_SEND_FAILED(AuditCategory.SYSTEM),
    // The outbound send queue overflowed and a notification was discarded. A dropped one-time code is a person
    // who cannot finish signing in, and the cause is upstream (a hanging provider), so it must be visible rather
    // than inferred from its absence.
    NOTIFICATION_DROPPED(AuditCategory.SYSTEM),
    // Branding is the same phishing surface as the templates: the logo and product name are what makes a mail
    // or a sign-in screen look like it came from this company rather than somebody else.
    BRANDING_CHANGED(AuditCategory.ADMIN),
    // Portal settings decide how the consoles behave for everyone in the tier, including how long an elevation
    // lasts — so a change is worth attributing even when it looks cosmetic.
    PORTAL_SETTINGS_CHANGED(AuditCategory.ADMIN),
    // A profile MAPPING is how a source's values reach the tenant's own attributes, and an attribute can decide
    // an ABAC policy. Re-aiming one changes what the directory is allowed to say about a person.
    PROFILE_MAPPING_CHANGED(AuditCategory.ADMIN),
    // Onboarding provisions a whole tenant and invites its first administrator. ORGANIZATION_CREATED covers the
    // direct admin route only, so without these a tenant could appear with no record of who asked for it, and a
    // re-issued admin invitation — a fresh credential sent to an address — left no trace at all.
    TENANT_ONBOARDING_STARTED(AuditCategory.ADMIN),
    TENANT_ADMIN_REINVITED(AuditCategory.ADMIN),
    // A template is the wording a person receives FROM this IdP, so editing one is a phishing surface in its
    // own right — the same reason the settings above are audited, one step further along the same path.
    EMAIL_TEMPLATE_CHANGED(AuditCategory.ADMIN),
    SMTP_SETTINGS_CHANGED(AuditCategory.ADMIN),
    SMS_SETTINGS_CHANGED(AuditCategory.ADMIN),
    SCIM_TOKEN_CHANGED(AuditCategory.ADMIN),
    NETWORK_ZONE_CHANGED(AuditCategory.ADMIN),
    APP_ASSIGNMENT_CHANGED(AuditCategory.AUTHORIZATION),
    SIGNING_KEY_ROTATED(AuditCategory.ADMIN),
    // A directory connector designates an authoritative source of identity ATTRIBUTES, which auto-mapping can
    // turn into role and group grants. Reconfiguring one, re-aiming its mappings or running a sync are
    // therefore privilege-relevant, and each has to be attributable to an administrator after the fact.
    DIRECTORY_CONNECTOR_CHANGED(AuditCategory.ADMIN),
    IDENTITY_PROVIDER_CHANGED(AuditCategory.ADMIN),
    DIRECTORY_SYNC_RUN(AuditCategory.ADMIN),
    // A rule whose conditions read a directory-owned attribute was skipped: nobody who aimed that directory
    // could have made the grant by hand, so the directory must not be able to make it for them.
    MAPPING_RULE_DIRECTORY_SOURCE_UNAUTHORIZED(AuditCategory.AUTHORIZATION),

    // Platform / system
    SERVER_ERROR(AuditCategory.SYSTEM);

    private final AuditCategory category;
}
