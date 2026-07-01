package com.example.sso.portal.internal.domain;

import com.example.sso.portal.AppType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppPolicyRepository extends JpaRepository<AppPolicy, UUID> {

    Optional<AppPolicy> findByAppTypeAndAppId(AppType appType, String appId);

    void deleteByAppTypeAndAppId(AppType appType, String appId);
}
