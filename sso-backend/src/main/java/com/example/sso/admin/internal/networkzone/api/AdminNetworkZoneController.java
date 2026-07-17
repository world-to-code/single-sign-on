package com.example.sso.admin.internal.networkzone.api;

import com.example.sso.audit.Audited;
import com.example.sso.audit.AuditType;
import com.example.sso.session.networkzone.NetworkZoneService;
import com.example.sso.session.networkzone.NetworkZoneView;
import com.example.sso.shared.Page;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for the reusable network-zone catalog (named IP ranges referenced by session policies). */
@RestController
@RequestMapping("/api/admin/network-zones")
@RequiredArgsConstructor
public class AdminNetworkZoneController {

    private final NetworkZoneService zones;

    @GetMapping
    @RequirePermission(Permissions.NETWORK_ZONE_READ)
    public Page<NetworkZoneView> networkZones(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return Page.of(zones.list(), page, size);
    }

    @Audited(value = AuditType.NETWORK_ZONE_CHANGED)
    @PostMapping
    @RequirePermission(Permissions.NETWORK_ZONE_CREATE)
    @RequireStepUp
    public ResponseEntity<NetworkZoneView> createZone(@Valid @RequestBody AdminNetworkZoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(zones.create(request.toSpec()));
    }

    @Audited(value = AuditType.NETWORK_ZONE_CHANGED)
    @PutMapping("/{id}")
    @RequirePermission(Permissions.NETWORK_ZONE_UPDATE)
    @RequireStepUp
    public NetworkZoneView updateZone(@PathVariable UUID id, @Valid @RequestBody AdminNetworkZoneRequest request) {
        return zones.update(id, request.toSpec());
    }

    @Audited(value = AuditType.NETWORK_ZONE_CHANGED)
    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.NETWORK_ZONE_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> deleteZone(@PathVariable UUID id) {
        zones.delete(id);
        return ResponseEntity.noContent().build();
    }
}
