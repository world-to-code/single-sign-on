package com.example.sso.portal;

import com.example.sso.portal.AppAssignment.AppType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An application-level sign-on policy: the auth policy required to access an app (OIDC client / SAML SP)
 * by ANY user, independent of per-subject {@link AppAssignment}s. One row per (appType, appId).
 */
@Entity
@Table(name = "app_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_type", nullable = false, length = 8)
    private AppType appType;

    @Column(name = "app_id", nullable = false, length = 255)
    private String appId;

    @Column(name = "required_policy_id", nullable = false)
    private UUID requiredPolicyId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AppPolicy(AppType appType, String appId, UUID requiredPolicyId) {
        this.appType = appType;
        this.appId = appId;
        this.requiredPolicyId = requiredPolicyId;
    }
}
