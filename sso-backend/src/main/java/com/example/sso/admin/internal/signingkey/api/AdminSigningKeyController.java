package com.example.sso.admin.internal.signingkey.api;

import com.example.sso.admin.internal.shared.application.AdminService;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for rotating the OIDC and SAML signing keys. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSigningKeyController {

    private final AdminService adminService;

    @PostMapping("/keys/rotate")
    @RequirePermission(Permissions.KEY_ROTATE)
    public Map<String, String> rotateSigningKey() {
        return Map.of("activeKid", adminService.rotateSigningKey());
    }

    @PostMapping("/saml/keys/rotate")
    @RequirePermission(Permissions.KEY_ROTATE)
    public Map<String, String> rotateSamlSigningKey() {
        return Map.of("keyId", adminService.rotateSamlSigningKey());
    }
}
