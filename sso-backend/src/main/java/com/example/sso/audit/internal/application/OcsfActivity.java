package com.example.sso.audit.internal.application;

/**
 * One OCSF event class and the activity within it.
 *
 * <p>The class alone is too coarse to be useful — a SIEM rule watches "an account was disabled", not "an
 * account changed" — and the activity is what carries that. {@code type_uid}, which OCSF identifies an event
 * by, is {@code class_uid * 100 + activity_id}.
 *
 * <p>Why the audit CATEGORY cannot decide this: {@code ADMIN} holds both {@code USER_CREATED} (Account
 * Change) and {@code SMTP_SETTINGS_CHANGED} (API Activity), and {@code AUTHORIZATION} holds both
 * {@code USER_PERMISSIONS_UPDATED} (Authorize Session) and {@code ROLE_CREATED} (API Activity). Deriving the
 * class from the category would put configuration edits into account analytics and privilege grants into
 * neither — so every type is mapped explicitly, and the startup check makes that mandatory.
 */
record OcsfActivity(int classUid, int activityId) {

    // --- OCSF classes used here (Identity & Access Management, and one Application Activity) ---
    static final int ACCOUNT_CHANGE = 3001;
    static final int AUTHENTICATION = 3002;
    static final int AUTHORIZE_SESSION = 3003;
    static final int API_ACTIVITY = 6003;

    // --- Authentication (3002) ---
    static final OcsfActivity LOGON = new OcsfActivity(AUTHENTICATION, 1);
    static final OcsfActivity LOGOFF = new OcsfActivity(AUTHENTICATION, 2);
    static final OcsfActivity AUTH_TICKET = new OcsfActivity(AUTHENTICATION, 3);
    static final OcsfActivity AUTH_OTHER = new OcsfActivity(AUTHENTICATION, 99);

    // --- Account Change (3001) ---
    static final OcsfActivity ACCOUNT_CREATE = new OcsfActivity(ACCOUNT_CHANGE, 1);
    static final OcsfActivity ACCOUNT_ENABLE = new OcsfActivity(ACCOUNT_CHANGE, 2);
    static final OcsfActivity PASSWORD_RESET = new OcsfActivity(ACCOUNT_CHANGE, 4);
    static final OcsfActivity ACCOUNT_DISABLE = new OcsfActivity(ACCOUNT_CHANGE, 5);
    static final OcsfActivity ACCOUNT_DELETE = new OcsfActivity(ACCOUNT_CHANGE, 6);
    static final OcsfActivity ATTACH_POLICY = new OcsfActivity(ACCOUNT_CHANGE, 7);
    static final OcsfActivity ACCOUNT_LOCK = new OcsfActivity(ACCOUNT_CHANGE, 9);
    static final OcsfActivity MFA_ENABLE = new OcsfActivity(ACCOUNT_CHANGE, 10);
    static final OcsfActivity MFA_DISABLE = new OcsfActivity(ACCOUNT_CHANGE, 11);
    static final OcsfActivity ACCOUNT_UNLOCK = new OcsfActivity(ACCOUNT_CHANGE, 12);
    static final OcsfActivity DETACH_POLICY = new OcsfActivity(ACCOUNT_CHANGE, 14);
    static final OcsfActivity ACCOUNT_CHANGE_OTHER = new OcsfActivity(ACCOUNT_CHANGE, 99);

    // --- Authorize Session (3003) ---
    static final OcsfActivity ASSIGN_PRIVILEGES = new OcsfActivity(AUTHORIZE_SESSION, 1);
    static final OcsfActivity ASSIGN_GROUPS = new OcsfActivity(AUTHORIZE_SESSION, 2);
    static final OcsfActivity AUTHORIZE_OTHER = new OcsfActivity(AUTHORIZE_SESSION, 99);

    // --- API Activity (6003) ---
    static final OcsfActivity API_CREATE = new OcsfActivity(API_ACTIVITY, 1);
    static final OcsfActivity API_READ = new OcsfActivity(API_ACTIVITY, 2);
    static final OcsfActivity API_UPDATE = new OcsfActivity(API_ACTIVITY, 3);
    static final OcsfActivity API_DELETE = new OcsfActivity(API_ACTIVITY, 4);
    static final OcsfActivity API_OTHER = new OcsfActivity(API_ACTIVITY, 99);

    /** How OCSF identifies an event kind: the class and the activity, combined. */
    int typeUid() {
        return classUid * 100 + activityId;
    }
}
