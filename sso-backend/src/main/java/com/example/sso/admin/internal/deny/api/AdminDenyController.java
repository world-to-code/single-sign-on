package com.example.sso.admin.internal.deny.api;

import com.example.sso.admin.internal.deny.application.DenyAdminService;
import com.example.sso.admin.internal.shared.security.CanManageDenies;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.deny.DenySubjectKind;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for NEGATIVE permissions (deny): author a deny that withholds a permission from a user/role/group/org,
 * or lift one. Dedicated verbs — NOT a replace-the-whole-set body — so authoring a deny never clobbers a
 * concurrent grant. URL access requires a completed MFA login plus a fresh admin-console elevation token; each
 * method carries the mutating-permission floor ({@link CanManageDenies}) and a step-up, while the authoritative
 * per-subject authorization and the last-admin guard live in the deny service.
 */
@RestController
@RequestMapping("/api/admin/denies")
@RequiredArgsConstructor
public class AdminDenyController {

    private final DenyAdminService denyAdminService;

    @PostMapping
    @CanManageDenies
    @RequireStepUp
    public ResponseEntity<DenyCreatedResponse> create(@Valid @RequestBody DenyRequest request) {
        UUID id = denyAdminService.create(request.toSpec());
        return ResponseEntity.status(HttpStatus.CREATED).body(new DenyCreatedResponse(id.toString()));
    }

    @DeleteMapping("/{id}")
    @CanManageDenies
    @RequireStepUp
    public ResponseEntity<Void> lift(@PathVariable UUID id, @RequestParam DenySubjectKind kind) {
        denyAdminService.lift(id, kind);
        return ResponseEntity.noContent().build();
    }
}
