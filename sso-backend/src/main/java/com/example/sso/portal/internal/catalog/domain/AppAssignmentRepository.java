package com.example.sso.portal.internal.catalog.domain;

import com.example.sso.portal.application.AppType;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppAssignmentRepository extends JpaRepository<AppAssignment, UUID> {

    List<AppAssignment> findByAppTypeAndAppId(AppType appType, String appId);

    List<AppAssignment> findBySubjectTypeAndSubjectId(AppAssignment.SubjectType subjectType, UUID subjectId);

    List<AppAssignment> findBySubjectTypeAndSubjectIdIn(AppAssignment.SubjectType subjectType, Collection<UUID> subjectIds);

    boolean existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
            AppType appType, String appId, AppAssignment.SubjectType subjectType, UUID subjectId);
}
