package com.example.sso.metadata.internal.api;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.CsvImportPreview;
import com.example.sso.metadata.CsvImportService;
import com.example.sso.metadata.CsvTemplate;
import com.example.sso.metadata.CsvTemplateService;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartRequest;

/**
 * The acting tenant's profiles and the attributes each declares. A profile id is client-supplied, so the
 * service checks it belongs to the caller's organization before reading or writing through it.
 */
@RestController
@RequestMapping("/api/admin/profiles")
@RequiredArgsConstructor
public class ProfileAdminController {

    private final ProfileService profiles;
    private final AttributeDefinitionService definitions;
    private final ProfileMappingService mappings;
    private final CsvTemplateService templates;
    private final CsvImportService imports;

    @GetMapping
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_READ)
    public List<Profile> list() {
        return profiles.list();
    }

    /**
     * What an uploaded CSV WOULD do on this profile, having written none of it.
     *
     * <p>Carries the create permission and a step-up, not the schema-read one the template download uses: a
     * file here becomes accounts, and bulk account creation is a privilege-changing act however routine it
     * looks. It writes nothing, but it is the step an administrator authorises the write from, so it is gated
     * as the write.
     */
    @PostMapping("/{id}/csv-import/preview")
    @RequirePermission(Permissions.USER_CREATE)
    @RequireStepUp
    public CsvImportPreview previewCsvImport(@PathVariable UUID id, MultipartRequest request) {
        return imports.preview(id, request);
    }

    @GetMapping("/{id}/attributes")
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_READ)
    public List<AttributeDefinition> attributes(@PathVariable UUID id) {
        return definitions.definitionsIn(id);
    }

    /** The CSV an administrator fills in to create users on this profile — its columns ARE its attributes. */
    @GetMapping(value = "/{id}/csv-template", produces = "text/csv")
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_READ)
    public ResponseEntity<String> csvTemplate(@PathVariable UUID id) {
        CsvTemplate template = templates.templateFor(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(template.filename()).build().toString())
                .body(template.content());
    }

    /** What this profile feeds. A source profile's mappings are how its values reach the tenant's own. */
    @GetMapping("/{id}/mappings")
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_READ)
    public List<ProfileMapping> mappings(@PathVariable UUID id) {
        return mappings.mappingsFrom(id);
    }

    @PutMapping("/{id}/mappings")
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_WRITE)
    @RequireStepUp
    public ProfileMapping map(@PathVariable UUID id, @Valid @RequestBody ProfileMappingRequest request) {
        return mappings.map(id, request.sourceKey(), request.targetProfileId(), request.targetKey());
    }

    @DeleteMapping("/{id}/mappings/{mappingId}")
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_WRITE)
    @RequireStepUp
    public ResponseEntity<Void> unmap(@PathVariable UUID id, @PathVariable UUID mappingId) {
        // Scoped to THIS profile: the id is client-supplied, and a mapping belonging to another one must not
        // be deletable through this route.
        mappings.mappingsFrom(id).stream().filter(m -> m.id().equals(mappingId)).findFirst()
                .ifPresent(m -> mappings.unmap(m.id()));
        return ResponseEntity.noContent().build();
    }

    /** Upsert by key: the key is the identity within a profile, so re-declaring one redefines it in place. */
    @PostMapping("/{id}/attributes")
    @RequirePermission(Permissions.ATTRIBUTE_DEFINITION_WRITE)
    public AttributeDefinition saveAttribute(@PathVariable UUID id,
            @Valid @RequestBody AttributeDefinitionRequest request) {
        return definitions.save(id, request.toSpec());
    }
}
