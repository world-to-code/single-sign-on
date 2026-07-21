package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.SourceConfigurators;
import com.example.sso.metadata.internal.domain.ProfileEntity;
import com.example.sso.metadata.internal.domain.ProfileRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who can be held to an attribute, assembled across the modules that own the sources.
 *
 * <p>This used to live in {@code directory}, which meant directory answered for SCIM too — and answered
 * "nobody", because it looked for a connector and a SCIM source profile has none. The consequence was not a
 * refused grant an administrator could see; it was that SCIM-fed attributes could never grant a role or select
 * a policy, permanently and silently.
 */
@ExtendWith(MockitoExtension.class)
class AttributeSourceAuthorityImplTest {

    private static final UUID LDAP_PROFILE = UUID.randomUUID();
    private static final UUID SCIM_PROFILE = UUID.randomUUID();
    private static final UUID ADA = UUID.randomUUID();
    private static final UUID GRACE = UUID.randomUUID();

    @Mock private ProfileMappingService mappings;
    @Mock private ProfileRepository profiles;
    @Mock private SourceConfigurators directoryContributor;
    @Mock private SourceConfigurators scimContributor;

    private AttributeSourceAuthorityImpl authority(SourceConfigurators... contributors) {
        return new AttributeSourceAuthorityImpl(mappings, profiles, List.of(contributors));
    }

    private void filledBy(UUID... sourceProfileIds) {
        List<ProfileMapping> rows = Arrays.stream(sourceProfileIds)
                .map(id -> new ProfileMapping(UUID.randomUUID(), id, "src", UUID.randomUUID(), "clearance"))
                .toList();
        when(mappings.mappingsFilling(Set.of("clearance"))).thenReturn(rows);
    }

    private void profileIs(UUID id, ProfileKind kind) {
        ProfileEntity entity = mock(ProfileEntity.class);
        lenient().when(entity.getId()).thenReturn(id);
        lenient().when(entity.getKind()).thenReturn(kind);
        lenient().when(profiles.findAllById(any())).thenReturn(List.of(entity));
    }

    @Test
    void aDirectorySourceIsAnsweredByTheModuleThatOwnsConnectors() {
        filledBy(LDAP_PROFILE);
        profileIs(LDAP_PROFILE, ProfileKind.LDAP);
        when(directoryContributor.handles(ProfileKind.LDAP)).thenReturn(true);
        when(directoryContributor.configuratorsOf(Set.of(LDAP_PROFILE)))
                .thenReturn(new AttributeSourceAuthors(Set.of(ADA), true));

        AttributeSourceAuthors authors = authority(directoryContributor).authorsFilling(Set.of("clearance"));

        assertThat(authors.fullyAttributed()).isTrue();
        assertThat(authors.configurators()).containsExactly(ADA);
    }

    /** The bug this exists to fix: SCIM has no connector, so directory answered "nobody" for it forever. */
    @Test
    void aScimSourceIsAnsweredByTheModuleThatOwnsTokens() {
        filledBy(SCIM_PROFILE);
        profileIs(SCIM_PROFILE, ProfileKind.SCIM);
        when(directoryContributor.handles(ProfileKind.SCIM)).thenReturn(false);
        when(scimContributor.handles(ProfileKind.SCIM)).thenReturn(true);
        when(scimContributor.configuratorsOf(Set.of(SCIM_PROFILE)))
                .thenReturn(new AttributeSourceAuthors(Set.of(GRACE), true));

        AttributeSourceAuthors authors =
                authority(directoryContributor, scimContributor).authorsFilling(Set.of("clearance"));

        assertThat(authors.fullyAttributed()).isTrue();
        assertThat(authors.configurators()).containsExactly(GRACE);
    }

    /**
     * A kind nobody claims is unattributable, not absent. Answering with only the half we understand is how the
     * previous arrangement let an LDAP configurator vouch for a value a SCIM client had written.
     */
    @Test
    void aSourceNoContributorClaimsMakesTheAnswerIncomplete() {
        filledBy(SCIM_PROFILE);
        profileIs(SCIM_PROFILE, ProfileKind.SCIM);
        when(directoryContributor.handles(ProfileKind.SCIM)).thenReturn(false);

        AttributeSourceAuthors authors = authority(directoryContributor).authorsFilling(Set.of("clearance"));

        assertThat(authors.complete()).isFalse();
        assertThat(authors.fullyAttributed()).isFalse();
    }

    /** Nothing fills the key, so nobody vouches for it — and the guards refuse on that. */
    @Test
    void anAttributeNoSourceFillsIsNotAttributed() {
        when(mappings.mappingsFilling(Set.of("clearance"))).thenReturn(List.of());

        AttributeSourceAuthors authors = authority(directoryContributor).authorsFilling(Set.of("clearance"));

        assertThat(authors.fullyAttributed()).isFalse();
    }

    @Test
    void noKeysMeansNothingToVouchFor() {
        assertThat(authority(directoryContributor).authorsFilling(Set.of()).fullyAttributed()).isFalse();
    }
}
