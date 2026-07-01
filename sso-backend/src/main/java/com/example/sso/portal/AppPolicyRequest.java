package com.example.sso.portal;

/** Admin request to set (or clear, when blank/null) the app-level sign-on policy for an application. */
public record AppPolicyRequest(String requiredPolicyId) {
}
