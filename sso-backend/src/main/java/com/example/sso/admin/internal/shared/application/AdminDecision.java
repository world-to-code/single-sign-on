package com.example.sso.admin.internal.shared.application;

/**
 * An administrative check's answer together with the reason for it.
 *
 * <p>The boolean the callers already use is a fold over this ({@code permitted()}), rather than this being
 * computed alongside an unchanged predicate. A second method that decides the same thing independently is a
 * second source of truth with a friendlier name, and the two would eventually disagree.
 */
public record AdminDecision(boolean permitted, AdminRefusal refusal) {

    private static final AdminDecision PERMITTED = new AdminDecision(true, AdminRefusal.NONE);

    public static AdminDecision allow() {
        return PERMITTED;
    }

    public static AdminDecision refused(AdminRefusal refusal) {
        return new AdminDecision(false, refusal);
    }
}
