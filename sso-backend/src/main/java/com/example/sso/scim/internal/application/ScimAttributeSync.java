package com.example.sso.scim.internal.application;

import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import de.captaingoldfish.scim.sdk.common.resources.User;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Writes the attributes a SCIM client sent onto the local user, through the tenant's profile mappings.
 *
 * <p>SCIM used to set four fields and stop, so everything else a client sent — title, department, whatever the
 * enterprise extension carried — was parsed and discarded. What it may fill is now the tenant's decision:
 * nothing is written unless a mapping says the SCIM profile's attribute feeds one of theirs.
 *
 * <p>Values go through {@code applyFromDirectory}, so the same rule as a directory sync applies — SCIM may only
 * fill attributes the schema says a directory owns, and cannot invent one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ScimAttributeSync {

    private final ProfileService profiles;
    private final ProfileMappingService mappings;
    private final AttributeService attributes;

    void apply(UUID userId, User resource) {
        UUID tenantProfile = profiles.tenantProfile().map(Profile::id).orElse(null);
        UUID scimProfile = profiles.list().stream()
                .filter(profile -> profile.kind() == ProfileKind.SCIM)
                .map(Profile::id).findFirst().orElse(null);
        if (tenantProfile == null || scimProfile == null) {
            return; // nothing describes what SCIM sends here yet
        }
        for (ProfileMapping mapping : mappings.mappingsFrom(scimProfile)) {
            if (!mapping.targetProfileId().equals(tenantProfile)) {
                continue; // only the tenant's own profile; see DirectorySyncService for why
            }
            List<String> values = read(resource, mapping.sourceKey());
            if (values.isEmpty()) {
                continue; // absent is not an instruction to clear it
            }
            try {
                attributes.applyFromDirectory(EntityKind.USER, userId.toString(), mapping.targetKey(), values);
            } catch (RuntimeException refused) {
                // A target the schema does not let a directory own. Log the KEY, never the value — SCIM
                // payloads are personal data.
                log.warn("SCIM could not fill {}: {}", mapping.targetKey(), refused.getClass().getSimpleName());
            }
        }
    }

    /**
     * The value at {@code path} in the SCIM payload — dotted for a nested attribute ({@code name.givenName}),
     * and reading straight off the JSON so a client's schema extensions are reachable without this class
     * knowing about them.
     */
    private List<String> read(User resource, String path) {
        JsonNode node = resource;
        for (String segment : path.split("\\.")) {
            node = node == null ? null : node.get(segment);
        }
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(element -> {
                JsonNode value = element.isObject() ? element.get("value") : element;
                if (value != null && !value.isNull() && !value.asText().isBlank()) {
                    values.add(value.asText());
                }
            });
            return List.copyOf(values);
        }
        return node.asText().isBlank() ? List.of() : List.of(node.asText());
    }
}
