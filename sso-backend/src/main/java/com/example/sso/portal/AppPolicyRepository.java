package com.example.sso.portal;

import com.example.sso.portal.AppAssignment.AppType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppPolicyRepository extends JpaRepository<AppPolicy, UUID> {

    Optional<AppPolicy> findByAppTypeAndAppId(AppType appType, String appId);

    void deleteByAppTypeAndAppId(AppType appType, String appId);
}
