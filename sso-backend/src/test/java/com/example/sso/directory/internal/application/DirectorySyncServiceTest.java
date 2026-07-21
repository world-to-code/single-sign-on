package com.example.sso.directory.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectorySyncRun;
import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sync engine. Three decisions carry the weight: WHICH local account a directory entry is (correlation),
 * WHETHER the sync may write what it found (ownership, settled before the bind), and whether the outcome is
 * recorded no matter what happened (the run record). Everything else is counting.
 */
@ExtendWith(MockitoExtension.class)
class DirectorySyncServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID SOURCE_PROFILE = UUID.randomUUID();
    private static final UUID TENANT_PROFILE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-20T10:00:00Z");

    @Mock private LdapDirectoryClient ldap;
    @Mock private DirectoryClients clients;
    @Mock private ProfileMappingService mappings;
    @Mock private ProfileService profiles;
    @Mock private AttributeDefinitionService definitions;
    @Mock private SecretCipher cipher;
    @Mock private UserService users;
    @Mock private DirectorySyncWriter writer;

    private DirectorySyncService service;
    private DirectoryConnector connector;

    @BeforeEach
    void setUp() {
        service = new DirectorySyncService(clients, mappings, profiles, definitions, cipher, users, writer);
        lenient().when(clients.forKind(DirectoryConnectorKind.LDAP)).thenReturn(ldap);
        connector = DirectoryConnector.create(ORG, "corp", DirectoryConnectorKind.LDAP);
        connector.reconfigure("Corp LDAP", true, "ldap.corp.test", 636, true, false, "cn=svc",
                "encg:cipher", "dc=corp", "(objectClass=person)", "entryUUID");

        DirectorySyncRun started = DirectorySyncRun.started(CONNECTOR, ORG, NOW);
        ReflectionTestUtils.setField(started, "id", RUN);
        lenient().when(writer.start(connector)).thenReturn(started);
        lenient().when(writer.succeeded(eq(RUN), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(i -> finished(DirectorySyncRun.SUCCEEDED, i.getArgument(1), i.getArgument(2),
                        i.getArgument(3), i.getArgument(4), null));
        lenient().when(writer.failed(eq(RUN), any()))
                .thenAnswer(i -> finished(DirectorySyncRun.FAILED, 0, 0, 0, 0, i.getArgument(1)));

        lenient().when(cipher.decrypt("encg:cipher")).thenReturn("s3cret");
        Profile sourceProfile = new Profile(SOURCE_PROFILE, "corp LDAP", ProfileKind.LDAP, CONNECTOR, false, false);
        lenient().when(profiles.findByConnectorId(any())).thenReturn(Optional.of(sourceProfile));
        lenient().when(profiles.tenantProfile()).thenReturn(Optional.of(
                new Profile(TENANT_PROFILE, "acme", ProfileKind.TENANT, null, true, true)));
        lenient().when(mappings.mappingsFrom(SOURCE_PROFILE)).thenReturn(List.of(
                new ProfileMapping(UUID.randomUUID(), SOURCE_PROFILE, "department", TENANT_PROFILE,
                        "department")));
        lenient().when(definitions.definitionOf(EntityKind.USER, "department"))
                .thenReturn(Optional.of(definition(AttributeSource.DIRECTORY)));
    }

    /** The writer persists in its own transaction; here we only need a run object carrying the outcome. */
    private DirectorySyncRun finished(String status, int read, int matched, int updated, int skipped,
            String error) {
        DirectorySyncRun run = DirectorySyncRun.started(CONNECTOR, ORG, NOW);
        if (DirectorySyncRun.SUCCEEDED.equals(status)) {
            run.succeeded(NOW, read, matched, updated, skipped);
        } else {
            run.failed(NOW, error);
        }
        return run;
    }

    private AttributeDefinition definition(AttributeSource source) {
        return new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, "department", "Department", null,
                AttributeDataType.STRING, List.of(), false, false, source, 0);
    }

    private void directoryReturns(DirectoryEntry... entries) {
        when(ldap.readUsers(eq(connector), eq("s3cret"), any())).thenReturn(List.of(entries));
    }

    private DirectoryEntry entry(String externalId, String department) {
        return new DirectoryEntry(externalId, Map.of("department", List.of(department)));
    }

    // --- correlation -------------------------------------------------------------------------------------

    @Test
    void fillsTheAttributesOfAnAccountItCanCorrelate() {
        UUID userId = UUID.randomUUID();
        DirectoryEntry ada = entry("dir-1", "Sales");
        directoryReturns(ada);
        when(users.idsByExternalIdInOrg(any(), eq(ORG))).thenReturn(Map.of("dir-1", userId));
        when(writer.apply(eq(userId), any(), eq(ada))).thenReturn(true);

        DirectorySyncRun run = service.sync(connector);

        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.SUCCEEDED);
        verify(writer).succeeded(RUN, 1, 1, 1, 0);
    }

    /** One query for the whole page, not one RBAC hydration per entry for data the sync never reads. */
    @Test
    void correlatesTheWholePageInOneLookup() {
        directoryReturns(entry("dir-1", "Sales"), entry("dir-2", "Ops"), entry("dir-3", "Legal"));
        when(users.idsByExternalIdInOrg(any(), eq(ORG))).thenReturn(Map.of());

        service.sync(connector);

        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.captor();
        verify(users).idsByExternalIdInOrg(asked.capture(), eq(ORG));
        assertThat(asked.getValue()).containsExactlyInAnyOrder("dir-1", "dir-2", "dir-3");
    }

    /**
     * An entry with no local account is COUNTED, not created. Account creation belongs to SCIM and federation
     * JIT; doing it here too would give lifecycle two owners and let a mis-aimed connector fill a tenant with
     * accounts nobody asked for.
     */
    @Test
    void countsAnUnmatchedEntryInsteadOfCreatingAnAccount() {
        directoryReturns(entry("dir-unknown", "Sales"));
        when(users.idsByExternalIdInOrg(any(), eq(ORG))).thenReturn(Map.of());

        DirectorySyncRun run = service.sync(connector);

        verify(users, never()).createUser(any(), any());
        verify(writer, never()).apply(any(), any(), any());
        verify(writer).succeeded(RUN, 1, 0, 0, 1);
        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.SUCCEEDED); // not an error — just nothing to do
    }

    /** Only the mapped source attributes are asked for — a directory read is not a licence to hoover up PII. */
    @Test
    void asksTheDirectoryOnlyForWhatItMapped() {
        directoryReturns(entry("dir-1", "Sales"));
        when(users.idsByExternalIdInOrg(any(), eq(ORG))).thenReturn(Map.of());

        service.sync(connector);

        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.captor();
        verify(ldap).readUsers(eq(connector), eq("s3cret"), asked.capture());
        assertThat(asked.getValue()).containsExactly("department");
    }

    // --- ownership, settled before the bind ---------------------------------------------------------------

    /**
     * A target the schema says an administrator owns must never be filled by a directory. Deciding this BEFORE
     * the bind is the point: it costs nothing, names the key to fix, and leaves no tenant half-applied. A
     * half-run would also have meant binding to a remote host to do work we already knew we must refuse.
     */
    @Test
    void refusesBeforeContactingTheDirectoryWhenATargetIsLocallyOwned() {
        when(definitions.definitionOf(EntityKind.USER, "department"))
                .thenReturn(Optional.of(definition(AttributeSource.LOCAL)));

        DirectorySyncRun run = service.sync(connector);

        verify(ldap, never()).readUsers(any(), any(), any());
        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.FAILED);
        assertThat(run.getError()).contains("department");
    }

    /** An undeclared key would let a connector invent schema by writing to it. */
    @Test
    void refusesATargetNoDefinitionDeclares() {
        when(definitions.definitionOf(EntityKind.USER, "department")).thenReturn(Optional.empty());

        DirectorySyncRun run = service.sync(connector);

        verify(ldap, never()).readUsers(any(), any(), any());
        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.FAILED);
    }

    // --- the run record -----------------------------------------------------------------------------------

    /** Nobody is watching an unattended run, so a failure is only knowable because it is written down. */
    @Test
    void recordsWhyARunFailed() {
        when(ldap.readUsers(any(), any(), any())).thenThrow(new BadRequestException("unreachable"));

        DirectorySyncRun run = service.sync(connector);

        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.FAILED);
        assertThat(run.getError()).isNotBlank();
    }

    /** The upstream's own words could name DNs or filter fragments; the record carries ours instead. */
    @Test
    void theRecordedFailureDoesNotRepeatTheUpstreamsMessage() {
        when(ldap.readUsers(any(), any(), any()))
                .thenThrow(new BadRequestException("cn=svc,dc=corp bind failed"));

        service.sync(connector);

        ArgumentCaptor<String> recorded = ArgumentCaptor.captor();
        verify(writer).failed(eq(RUN), recorded.capture());
        assertThat(recorded.getValue()).doesNotContain("cn=svc").doesNotContain("dc=corp");
    }

    /** The run is on record BEFORE the directory is contacted, so a hang leaves evidence rather than silence. */
    @Test
    void recordsTheRunBeforeContactingTheDirectory() {
        directoryReturns(entry("dir-1", "Sales"));
        when(users.idsByExternalIdInOrg(any(), eq(ORG))).thenReturn(Map.of());

        service.sync(connector);

        InOrder order = Mockito.inOrder(writer, ldap);
        order.verify(writer).start(connector);
        order.verify(ldap).readUsers(any(), any(), any());
    }

    /** A connector with nothing mapped would read a directory and write nothing — say so rather than pretend. */
    @Test
    void aConnectorWithNoMappingsDoesNotTouchTheDirectory() {
        when(mappings.mappingsFrom(SOURCE_PROFILE)).thenReturn(List.of());

        DirectorySyncRun run = service.sync(connector);

        verify(ldap, never()).readUsers(any(), any(), any());
        assertThat(run.getStatus()).isEqualTo(DirectorySyncRun.SUCCEEDED);
        assertThat(run.getEntriesRead()).isZero();
    }
}
