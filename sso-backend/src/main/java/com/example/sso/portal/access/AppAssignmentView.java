package com.example.sso.portal.access;

import com.example.sso.portal.internal.catalog.domain.AppAssignment;

/** An application assignment as shown in the admin dashboard. */
public record AppAssignmentView(String id, String appType, String appId, String appName,
                                String subjectType, String subjectId, String subjectName,
                                String requiredPolicyId) {

    /** Projects an assignment, given the already-resolved app and subject display names. */
    public static AppAssignmentView of(AppAssignment assignment, String appName, String subjectName) {
        return new AppAssignmentView(assignment.getId().toString(), assignment.getAppType().name(),
                assignment.getAppId(), appName, assignment.getSubjectType().name(),
                assignment.getSubjectId().toString(), subjectName,
                assignment.getRequiredPolicyId() == null ? null : assignment.getRequiredPolicyId().toString());
    }
}
