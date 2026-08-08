package com.example.sso.user.rbac;

/**
 * Why a permission ended up where it did — the rung of the specificity ladder that spoke.
 *
 * <p>It is a separate axis from the outcome because several rungs produce the same outcome for opposite
 * causes: a permission absent because a role deny cut it and one absent because nobody ever granted it are
 * both "not held", and only the reason distinguishes a deliberate refusal from silence.
 *
 * <p>Public because an administrator is the reader: the console has to distinguish a deliberate
 * refusal from silence, and a set-subtraction guess cannot.
 *
 * <p>Each value must be produced by the branch that decided, never inferred afterwards from the outcome. An
 * inferred reason is a plausible answer rather than a true one, and the reader cannot tell which they have.
 */
public enum DecisionReason {

    /** A platform {@code ROLE_ADMIN} holder is exempt from every deny; no rung was consulted at all. */
    SUPER_ADMIN_EXEMPT,

    ALLOWED_AT_USER_LEVEL,
    ALLOWED_AT_ROLE_LEVEL,

    DENIED_AT_USER_LEVEL,
    DENIED_AT_ROLE_LEVEL,
    DENIED_AT_GROUP_LEVEL,
    DENIED_AT_ORG_LEVEL,

    /** The absolute veto. Applied after the ladder, so it needs its own reason or the row would credit the
     *  grant that appeared to win. */
    PLATFORM_VETO,

    /** Neither granted nor denied anywhere — the permission simply does not apply to this user. */
    NO_LEVEL_SPOKE
}
