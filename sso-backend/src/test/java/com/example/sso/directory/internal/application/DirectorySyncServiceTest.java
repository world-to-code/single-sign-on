package com.example.sso.directory.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.directory.internal.domain.DirectoryAttributeMapping;
import com.example.sso.directory.internal.domain.DirectoryAttributeMappingRepository;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectorySyncRun;
import com.example.sso.directory.internal.domain.DirectorySyncRunRepository;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sync engine. Two decisions carry the weight: WHICH local account a directory entry is (correlation), and
 * WHETHER the sync may write what it found (ownership). Everything else is counting.
 */
@ExtendWith(MockitoExtension.class)
class DirectorySyncServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-20T10:00:00Z");

    @Mock private LdapDirectoryClient ldap;
    @Mock private DirectoryAttributeMappingRepository mappings;
    @Mock private DirectorySyncRunRepository runs;
    @Mock private SecretCipher cipher;
    @Mock private UserService users;
    @Mock private AttributeService attributes;

    private DirectorySyncService service;
    private DirectoryConnector connector;

    @BeforeEach
    void setUp() {
        service = new DirectorySyncService(ldap, mappings, runs, cipher, users, attributes,
                Clock.fixed(NOW, ZoneOffset.UTC));
        connector = DirectoryConnector.create(ORG, "corp", DirectoryConnectorKind.LDAP);
        connector.reconfigure("Corp LDAP", true, "ldap.corp.test", 636, true, false, "cn=svc",
                "encg:cipher", "dc=corp", "(objectClass=person)", "entryUUID");
        lenient().when(cipher.decrypt("encg:cipher")).thenReturn("s3cret");
        lenient().when(runs.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(mappings.findByConnectorIdOrderBySourceAttribute(any())).thenReturn(List.of(
                DirectoryAttributeMapping.create(CONNECTOR, ORG, "department", "department")));
    }

    private void directoryReturns(DirectoryEntry... entries) {
        when(ldap.readUsers(eq(connector), eq("s3cret"), any())).thenReturn(List.of(entries));
    }

    private DirectoryEntry entry(String externalId, String department) {
        return new DirectoryEntry(externalId, Map.of("department", List.of(department)));
    }

    private UserAccount account(UUID id) {
        UserAccount user = org.mockito.Mockito.mock(UserAccount.class);
        lenient().when(user.getId()).thenReturn(id);
        return user;
    }

    // --- correlation -------------------------------------------------------------------------------------

    @Test
    void fillsTheAttributesOfAnAccountItCanCorrelate() {
        UUID userId = UUID.randomUUID();
        UserAccount user = account(userId);
        directoryReturns(entry("dir-1", "Sales"));
        when(users.findByExternalIdInOrg("dir-1", ORG)).thenReturn(Optional.of(user));

        DirectorySyncRun run = service.sync(connector);

        verify(attributes).applyFromDirectory(EntityKind.USER, userId.toString(), "department", List.of("Sales"));
        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.SUCCEEDED);
        assertThat(run.getMatched()).isEqualTo(1);
        assertThat(run.getUpdated()).isEqualTo(1);
    }

    /**
     * An entry with no local account is COUNTED, not created. Account creation belongs to SCIM and federation
     * JIT; doing it here too would give lifecycle two owners and let a mis-aimed connector fill a tenant with
     * accounts nobody asked for.
     */
    @Test
    void countsAnUnmatchedEntryInsteadOfCreatingAnAccount() {
        directoryReturns(entry("dir-unknown", "Sales"));
        when(users.findByExternalIdInOrg("dir-unknown", ORG)).thenReturn(Optional.empty());

        DirectorySyncRun run = service.sync(connector);

        verify(users, never()).createUser(any(), any());
        verify(attributes, never()).applyFromDirectory(any(), any(), any(), any());
        assertThat(run.getSkipped()).isEqualTo(1);
        assertThat(run.getMatched()).isZero();
        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.SUCCEEDED); // not an error — just nothing to do
    }

    @Test
    void writesOnlyTheMappedAttributes() {
        UUID userId = UUID.randomUUID();
        UserAccount user = account(userId);
        when(ldap.readUsers(eq(connector), eq("s3cret"), any())).thenReturn(List.of(
                new DirectoryEntry("dir-1", Map.of("department", List.of("Sales"), "title", List.of("Lead")))));
        when(users.findByExternalIdInOrg("dir-1", ORG)).thenReturn(Optional.of(user));

        service.sync(connector);

        verify(attributes).applyFromDirectory(EntityKind.USER, userId.toString(), "department", List.of("Sales"));
        verify(attributes, never()).applyFromDirectory(any(), any(), eq("title"), any());
    }

    /** Only the mapped source attributes are asked for — a directory read is not a licence to hoover up PII. */
    @Test
    void asksTheDirectoryOnlyForWhatItMapped() {
        directoryReturns(entry("dir-1", "Sales"));
        when(users.findByExternalIdInOrg(anyString(), any())).thenReturn(Optional.empty());

        service.sync(connector);

        ArgumentCaptor<java.util.Collection<String>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(ldap).readUsers(eq(connector), eq("s3cret"), asked.capture());
        assertThat(asked.getValue()).containsExactly("department");
    }

    // --- ownership and failure ---------------------------------------------------------------------------

    /**
     * A target the schema says an administrator owns must not be overwritten. The store refuses it; the sync's
     * job is to carry on and report, not to abort the whole run over one mis-mapped attribute.
     */
    @Test
    void aRefusedAttributeDoesNotAbortTheRun() {
        UUID userId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UserAccount first = account(userId);
        UserAccount second = account(secondId);
        directoryReturns(entry("dir-1", "Sales"), entry("dir-2", "Ops"));
        when(users.findByExternalIdInOrg("dir-1", ORG)).thenReturn(Optional.of(first));
        when(users.findByExternalIdInOrg("dir-2", ORG)).thenReturn(Optional.of(second));
        doThrow(com.example.sso.shared.error.ConflictException.of("attribute.locallyOwned", "department"))
                .when(attributes)
                .applyFromDirectory(EntityKind.USER, userId.toString(), "department", List.of("Sales"));

        DirectorySyncRun run = service.sync(connector);

        verify(attributes).applyFromDirectory(EntityKind.USER, secondId.toString(), "department", List.of("Ops"));
        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.SUCCEEDED);
        assertThat(run.getUpdated()).isEqualTo(1); // the refused one did not count as updated
    }

    /** Nobody is watching an unattended run, so a failure is only knowable because it is written down. */
    @Test
    void recordsWhyARunFailed() {
        when(ldap.readUsers(any(), any(), any()))
                .thenThrow(new com.example.sso.shared.error.BadRequestException("unreachable"));

        DirectorySyncRun run = service.sync(connector);

        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.FAILED);
        assertThat(run.getError()).isNotBlank();
        assertThat(run.getFinishedAt()).isEqualTo(NOW);
    }

    /** A connector with nothing mapped would read a directory and write nothing — say so rather than pretend. */
    @Test
    void aConnectorWithNoMappingsDoesNotTouchTheDirectory() {
        when(mappings.findByConnectorIdOrderBySourceAttribute(any())).thenReturn(List.of());

        DirectorySyncRun run = service.sync(connector);

        verify(ldap, never()).readUsers(any(), any(), any());
        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.SUCCEEDED);
        assertThat(run.getEntriesRead()).isZero();
    }
}
