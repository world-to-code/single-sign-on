package com.example.sso.admin.internal.relyingparty.api;

import com.example.sso.saml.relyingparty.RelyingPartyRequest;
import com.example.sso.saml.relyingparty.RelyingPartyView;
import com.example.sso.saml.relyingparty.SamlRelyingPartyAdminService;
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

/** Admin API for SAML relying party (service provider) registration. */
@RestController
@RequestMapping("/api/admin/saml/relying-parties")
@RequiredArgsConstructor
public class AdminRelyingPartyController {

    private final SamlRelyingPartyAdminService samlRelyingParties;

    @GetMapping
    @RequirePermission(Permissions.SAML_READ)
    public Page<RelyingPartyView> relyingParties(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return samlRelyingParties.list(page, size);
    }

    @PostMapping
    @RequirePermission(Permissions.SAML_CREATE)
    @RequireStepUp
    public ResponseEntity<RelyingPartyView> createRelyingParty(@Valid @RequestBody RelyingPartyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(samlRelyingParties.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SAML_UPDATE)
    @RequireStepUp
    public RelyingPartyView updateRelyingParty(@PathVariable UUID id, @Valid @RequestBody RelyingPartyRequest request) {
        return samlRelyingParties.update(id, request);
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SAML_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> deleteRelyingParty(@PathVariable UUID id) {
        samlRelyingParties.delete(id);
        return ResponseEntity.noContent().build();
    }
}
