package com.example.sso.directory.internal.api;

import com.example.sso.audit.Audited;
import com.example.sso.audit.AuditType;
import com.example.sso.directory.DirectoryAttributeMappingView;
import com.example.sso.directory.DirectoryConnectorService;
import com.example.sso.directory.DirectoryConnectorView;
import com.example.sso.directory.DirectorySyncRunView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

/**
 * Admin API for the acting tenant's directory connections. Writes are step-up gated because a connector holds
 * a bind credential for someone else's directory, and the view NEVER carries it. Org scoping is enforced in
 * the service, which fails closed for a bound-but-orgless caller.
 */
@RestController
@RequestMapping("/api/admin/directory-connectors")
@RequiredArgsConstructor
public class DirectoryConnectorAdminController {

    private final DirectoryConnectorService service;

    @GetMapping
    @RequirePermission(Permissions.DIRECTORY_CONNECTOR_READ)
    public List<DirectoryConnectorView> list() {
        return service.list();
    }

    @Audited(AuditType.DIRECTORY_CONNECTOR_CHANGED)
    @PutMapping("/{name}")
    @RequirePermission(Permissions.DIRECTORY_CONNECTOR_WRITE)
    @RequireStepUp
    public DirectoryConnectorView save(@PathVariable String name,
            @Valid @RequestBody DirectoryConnectorRequest request) {
        service.save(request.toSpec(name));
        return service.get(name);
    }

    @Audited(AuditType.DIRECTORY_CONNECTOR_CHANGED)
    @DeleteMapping("/{name}")
    @RequirePermission(Permissions.DIRECTORY_CONNECTOR_WRITE)
    @RequireStepUp
    public ResponseEntity<Void> delete(@PathVariable String name) {
        service.delete(name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{name}/mappings")
    @RequirePermission(Permissions.DIRECTORY_CONNECTOR_READ)
    public List<DirectoryAttributeMappingView> mappings(@PathVariable String name) {
        return service.mappings(name);
    }

    @Audited(AuditType.DIRECTORY_CONNECTOR_CHANGED)
    @PutMapping("/{name}/mappings")
    @RequireStepUp
    @RequirePermission(Permissions.DIRECTORY_CONNECTOR_WRITE)
    public List<DirectoryAttributeMappingView> map(@PathVariable String name,
            @Valid @RequestBody DirectoryAttributeMappingRequest request) {
        service.mapAttribute(name, request.sourceAttribute(), request.targetKey());
        return service.mappings(name);
    }

    @Audited(AuditType.DIRECTORY_CONNECTOR_CHANGED)
    @DeleteMapping("/{name}/mappings/{mappingId}")
    @RequireStepUp
    @RequirePermission(Permissions.DIRECTORY_CONNECTOR_WRITE)
    public ResponseEntity<Void> unmap(@PathVariable String name, @PathVariable UUID mappingId) {
        service.unmapAttribute(name, mappingId);
        return ResponseEntity.noContent().build();
    }

    /** Runs it now. Step-up gated like the writes: it reaches out with the stored credential. */
    @Audited(AuditType.DIRECTORY_SYNC_RUN)
    @PostMapping("/{name}/sync")
    @RequirePermission(Permissions.DIRECTORY_CONNECTOR_WRITE)
    @RequireStepUp
    public DirectorySyncRunView syncNow(@PathVariable String name) {
        return service.syncNow(name);
    }

    @GetMapping("/{name}/runs")
    @RequirePermission(Permissions.DIRECTORY_CONNECTOR_READ)
    public List<DirectorySyncRunView> runs(@PathVariable String name,
            @RequestParam(defaultValue = "20") int limit) {
        return service.runs(name, limit);
    }
}
