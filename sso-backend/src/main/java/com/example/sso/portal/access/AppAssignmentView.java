package com.example.sso.portal.access;

/** An application assignment as shown in the admin dashboard. */
public record AppAssignmentView(String id, String appType, String appId, String appName,
                                String subjectType, String subjectId, String subjectName,
                                String requiredPolicyId) {
}
