package com.example.sso.portal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AppAssignmentRepository extends JpaRepository<AppAssignment, UUID> {

    List<AppAssignment> findByAppTypeAndAppId(AppAssignment.AppType appType, String appId);

    List<AppAssignment> findBySubjectTypeAndSubjectId(AppAssignment.SubjectType subjectType, UUID subjectId);

    List<AppAssignment> findBySubjectTypeAndSubjectIdIn(AppAssignment.SubjectType subjectType, Collection<UUID> subjectIds);

    boolean existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
            AppAssignment.AppType appType, String appId, AppAssignment.SubjectType subjectType, UUID subjectId);
}
