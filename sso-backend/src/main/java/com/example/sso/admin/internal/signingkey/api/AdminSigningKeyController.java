package com.example.sso.admin.internal.signingkey.api;

import com.example.sso.admin.internal.shared.application.AdminService;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for rotating the OIDC and SAML signing keys and the acting tier's JWKS retention. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSigningKeyController {

    private final AdminService adminService;

    @PostMapping("/keys/rotate")
    @RequirePermission(Permissions.KEY_ROTATE)
    @RequireStepUp
    public ResponseEntity<SigningKeyRotationResponse> rotateSigningKey() {
        return ResponseEntity.ok(new SigningKeyRotationResponse(adminService.rotateSigningKey()));
    }

    /** The acting tier's JWKS retention (rotated-away keys kept published for verification overlap). */
    @GetMapping("/keys/retention")
    @RequirePermission(Permissions.KEY_ROTATE)
    public ResponseEntity<SigningKeyRetentionResponse> signingKeyRetention() {
        return ResponseEntity.ok(new SigningKeyRetentionResponse(adminService.signingKeyRetention()));
    }

    @PutMapping("/keys/retention")
    @RequirePermission(Permissions.KEY_ROTATE)
    @RequireStepUp
    public ResponseEntity<SigningKeyRetentionResponse> updateSigningKeyRetention(
            @Valid @RequestBody SigningKeyRetentionRequest request) {
        return ResponseEntity.ok(new SigningKeyRetentionResponse(
                adminService.updateSigningKeyRetention(request.retainedInactiveKeys())));
    }

    @PostMapping("/saml/keys/rotate")
    @RequirePermission(Permissions.KEY_ROTATE)
    @RequireStepUp
    public ResponseEntity<SamlSigningKeyRotationResponse> rotateSamlSigningKey() {
        return ResponseEntity.ok(new SamlSigningKeyRotationResponse(adminService.rotateSamlSigningKey()));
    }
}
