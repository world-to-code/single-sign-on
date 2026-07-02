package com.example.sso.admin.internal.relyingparty.api;

import com.example.sso.saml.RelyingPartyRequest;
import com.example.sso.saml.RelyingPartyView;
import com.example.sso.saml.SamlRelyingPartyAdminService;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

/** Admin API for SAML relying party (service provider) registration. */
@RestController
@RequestMapping("/api/admin/saml/relying-parties")
@RequiredArgsConstructor
public class AdminRelyingPartyController {

    private final SamlRelyingPartyAdminService samlRelyingParties;

    @GetMapping
    @RequirePermission(Permissions.SAML_READ)
    public List<RelyingPartyView> relyingParties() {
        return samlRelyingParties.list();
    }

    @PostMapping
    @RequirePermission(Permissions.SAML_CREATE)
    public ResponseEntity<RelyingPartyView> createRelyingParty(@Valid @RequestBody RelyingPartyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(samlRelyingParties.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SAML_UPDATE)
    public RelyingPartyView updateRelyingParty(@PathVariable UUID id, @Valid @RequestBody RelyingPartyRequest request) {
        return samlRelyingParties.update(id, request);
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SAML_DELETE)
    public ResponseEntity<Void> deleteRelyingParty(@PathVariable UUID id) {
        samlRelyingParties.delete(id);
        return ResponseEntity.noContent().build();
    }
}
