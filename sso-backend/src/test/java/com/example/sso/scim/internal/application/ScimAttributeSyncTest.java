package com.example.sso.scim.internal.application;

import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.resources.complex.Name;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Email;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a SCIM client sends beyond the four fields we already stored.
 *
 * <p>The tenant decides what may land: nothing is written unless a mapping says the SCIM profile's attribute
 * feeds one of theirs. That is the same rule the directory sync follows, and for the same reason — a client
 * must not be able to invent schema by pushing a field nobody declared.
 */
@ExtendWith(MockitoExtension.class)
class ScimAttributeSyncTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SCIM = UUID.randomUUID();

    @Mock private ProfileService profiles;
    @Mock private ProfileMappingService mappings;
    @Mock private AttributeService attributes;

    private ScimAttributeSync sync;

    @BeforeEach
    void setUp() {
        sync = new ScimAttributeSync(profiles, mappings, attributes);
        lenient().when(profiles.tenantProfile()).thenReturn(Optional.of(
                new Profile(TENANT, "acme", ProfileKind.TENANT, null, true, true)));
        lenient().when(profiles.list()).thenReturn(List.of(
                new Profile(SCIM, "SCIM", ProfileKind.SCIM, null, false, false)));
    }

    private void maps(String sourceKey, String targetKey) {
        when(mappings.mappingsFrom(SCIM)).thenReturn(List.of(
                new ProfileMapping(UUID.randomUUID(), SCIM, sourceKey, TENANT, targetKey)));
    }

    private User user() {
        User resource = User.builder().userName("ada").build();
        resource.setName(Name.builder().givenName("Ada").familyName("Lovelace").build());
        resource.setEmails(List.of(Email.builder().value("ada@example.com").primary(true).build()));
        return resource;
    }

    @Test
    void writesAMappedAttribute() {
        maps("title", "jobTitle");
        User resource = user();
        resource.setTitle("Engineer");

        sync.apply(USER, resource);

        verify(attributes).applyFromDirectory(EntityKind.USER, USER.toString(), "jobTitle",
                List.of("Engineer"));
    }

    /** A nested SCIM attribute is reachable by its dotted path, so `name.givenName` can fill `firstName`. */
    @Test
    void readsANestedAttributeByItsDottedPath() {
        maps("name.givenName", "firstName");

        sync.apply(USER, user());

        verify(attributes).applyFromDirectory(EntityKind.USER, USER.toString(), "firstName", List.of("Ada"));
    }

    /** A multi-valued SCIM attribute yields its `value` members, not the JSON objects around them. */
    @Test
    void readsTheValuesOutOfAMultiValuedAttribute() {
        maps("emails", "workEmail");

        sync.apply(USER, user());

        verify(attributes).applyFromDirectory(EntityKind.USER, USER.toString(), "workEmail",
                List.of("ada@example.com"));
    }

    /** Nothing is written for a field nobody mapped — a client cannot invent schema by pushing it. */
    @Test
    void ignoresAnAttributeNobodyMapped() {
        when(mappings.mappingsFrom(SCIM)).thenReturn(List.of());
        User resource = user();
        resource.setTitle("Engineer");

        sync.apply(USER, resource);

        verify(attributes, never()).applyFromDirectory(any(), any(), any(), any());
    }

    /** Absent is not an instruction to clear: a payload omitting a mapped field leaves the value alone. */
    @Test
    void leavesAMappedAttributeAloneWhenThePayloadOmitsIt() {
        maps("title", "jobTitle");

        sync.apply(USER, user()); // no title set

        verify(attributes, never()).applyFromDirectory(any(), any(), any(), any());
    }

    /**
     * entity_attribute.attr_value is varchar(255) and SCIM input is unbounded. Reaching the insert would fail
     * the whole provisioning call on a constraint the client cannot see — and, inside the caller's
     * transaction, the swallowed violation would surface at commit where nothing can handle it.
     */
    @Test
    void refusesAValueTooLongForTheAttributeStore() {
        maps("title", "jobTitle");
        User resource = user();
        resource.setTitle("x".repeat(256));

        sync.apply(USER, resource);

        verify(attributes, never()).applyFromDirectory(any(), any(), any(), any());
    }

    /** A mapping onto any profile but the tenant's is ignored — the same rule the directory sync follows. */
    @Test
    void ignoresAMappingOntoAnotherProfile() {
        when(mappings.mappingsFrom(SCIM)).thenReturn(List.of(
                new ProfileMapping(UUID.randomUUID(), SCIM, "title", UUID.randomUUID(), "jobTitle")));
        User resource = user();
        resource.setTitle("Engineer");

        sync.apply(USER, resource);

        verify(attributes, never()).applyFromDirectory(any(), any(), any(), any());
    }
}
