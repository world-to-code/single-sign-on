package com.example.sso.admin.internal.client.api;

import com.example.sso.admin.internal.client.application.ClientAdminService;
import com.example.sso.admin.internal.client.application.ClientCreated;
import com.example.sso.admin.internal.client.application.ClientView;
import com.example.sso.admin.internal.client.application.CreateClientRequest;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for OIDC/OAuth2 client (relying party) registration. */
@RestController
@RequestMapping("/api/admin/clients")
@RequiredArgsConstructor
public class AdminClientController {

    private final ClientAdminService clientAdminService;

    @GetMapping
    @RequirePermission(Permissions.CLIENT_READ)
    public List<ClientView> clients() {
        return clientAdminService.listClients();
    }

    @PostMapping
    @RequirePermission(Permissions.CLIENT_CREATE)
    public ResponseEntity<ClientCreated> createClient(@Valid @RequestBody CreateClientRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientAdminService.createClient(request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.CLIENT_DELETE)
    public ResponseEntity<Void> deleteClient(@PathVariable String id) {
        clientAdminService.deleteClient(id);
        return ResponseEntity.noContent().build();
    }
}
