package com.example.sso.directory.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.directory.DirectoryConnectorSpec;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectoryConnectorRepository;
import com.example.sso.directory.internal.domain.DirectorySyncRunRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The connector's write path. It stores a bind credential for someone else's directory, so the transport rules
 * and the write-only secret handling are the security surface — everything else is bookkeeping.
 */
@ExtendWith(MockitoExtension.class)
class DirectoryConnectorServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock private DirectoryConnectorRepository connectors;
    @Mock private ProfileMappingService mappings;
    @Mock private ProfileService profiles;
    @Mock private DirectorySyncRunRepository runs;
    @Mock private DirectorySyncService sync;
    @Mock private SecretCipher cipher;
    @Mock private OutboundHostValidator hostValidator;
    @Mock private OrgContext orgContext;
    @Mock private UserService users;
    @Mock private ApplicationEventPublisher events;

    private DirectoryConnectorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DirectoryConnectorServiceImpl(connectors, profiles, mappings, runs, sync, cipher,
                hostValidator, orgContext, events, users);
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(cipher.encrypt(any())).thenReturn("encg:cipher");
        lenient().when(connectors.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(connectors.findByOrgIdAndName(ORG, "corp")).thenReturn(Optional.empty());
    }

    private DirectoryConnectorSpec spec(String password, int port, boolean useSsl, boolean startTls) {
        return new DirectoryConnectorSpec("corp", "Corp LDAP", DirectoryConnectorKind.LDAP, true,
                "ldap.corp.test", port, useSsl, startTls, "cn=svc", password, "dc=corp",
                "(objectClass=person)", "entryUUID");
    }

    private DirectoryConnector stored() {
        DirectoryConnector connector = DirectoryConnector.create(ORG, "corp", DirectoryConnectorKind.LDAP);
        connector.reconfigure("Corp", true, "ldap.corp.test", 636, true, false, "cn=svc", "encg:stored",
                "dc=corp", "(objectClass=person)", "entryUUID");
        return connector;
    }

    // --- transport ---------------------------------------------------------------------------------------

    /** A bind puts the directory credential on the wire; no configuration may send it in the clear. */
    @Test
    void refusesAConnectorThatWouldBindOverCleartext() {
        assertThatThrownBy(() -> service.save(spec("s3cret", 389, false, false)))
                .isInstanceOf(BadRequestException.class);
        verify(connectors, never()).save(any());
    }

    @Test
    void acceptsImplicitTlsAndStartTls() {
        service.save(spec("s3cret", 636, true, false));
        service.save(spec("s3cret", 389, false, true));

        verify(connectors, org.mockito.Mockito.times(2)).save(any());
    }

    /** An arbitrary port aims a bind credential at something that need not be a directory at all. */
    @Test
    void refusesAPortThatIsNotADirectoryPort() {
        assertThatThrownBy(() -> service.save(spec("s3cret", 8080, true, false)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesAHostTheSsrfGuardRejects() {
        doThrow(new BadRequestException("internal address")).when(hostValidator).validate("ldap.corp.test");

        assertThatThrownBy(() -> service.save(spec("s3cret", 636, true, false)))
                .isInstanceOf(BadRequestException.class);
        verify(connectors, never()).save(any());
    }

    // --- the write-only secret ---------------------------------------------------------------------------

    @Test
    void encryptsASuppliedBindPassword() {
        service.save(spec("s3cret", 636, true, false));

        verify(cipher).encrypt("s3cret");
    }

    /** The view never returns the password, so editing another field must not silently clear it. */
    @Test
    void aBlankPasswordOnAnUpdateKeepsTheStoredOne() {
        DirectoryConnector existing = stored();
        when(connectors.findByOrgIdAndName(ORG, "corp")).thenReturn(Optional.of(existing));

        service.save(spec("  ", 636, true, false));

        assertThat(existing.getBindPasswordEncrypted()).isEqualTo("encg:stored");
        verify(cipher, never()).encrypt(any());
    }

    /**
     * The credential belongs to a DESTINATION, not to a row. Someone who may edit the connector but may not
     * read the password could otherwise repoint {@code host} at a directory they control, leave the password
     * blank, and have us bind the tenant's real corporate service account against their server — handing them
     * a credential that is typically broad read over the customer's whole estate. Moving the destination
     * therefore invalidates the stored secret; it has to be re-supplied in the same request.
     */
    @Test
    void repointingAConnectorMayNotCarryTheStoredCredentialToTheNewDestination() {
        DirectoryConnector existing = stored();
        when(connectors.findByOrgIdAndName(ORG, "corp")).thenReturn(Optional.of(existing));
        DirectoryConnectorSpec repointed = new DirectoryConnectorSpec("corp", "Corp LDAP",
                DirectoryConnectorKind.LDAP, true, "ldap.attacker.test", 636, true, false, "cn=svc", "",
                "dc=corp", "(objectClass=person)", "entryUUID");

        assertThatThrownBy(() -> service.save(repointed)).isInstanceOf(BadRequestException.class);
        assertThat(existing.getHost()).isEqualTo("ldap.corp.test"); // nothing was written
    }

    /** The same reasoning for the other two things that decide where the credential goes and who it binds as. */
    @Test
    void changingThePortOrTheBindDnAlsoInvalidatesTheStoredCredential() {
        DirectoryConnector existing = stored();
        when(connectors.findByOrgIdAndName(ORG, "corp")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.save(new DirectoryConnectorSpec("corp", "Corp LDAP",
                DirectoryConnectorKind.LDAP, true, "ldap.corp.test", 389, false, true, "cn=svc", "",
                "dc=corp", "(objectClass=person)", "entryUUID"))).isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> service.save(new DirectoryConnectorSpec("corp", "Corp LDAP",
                DirectoryConnectorKind.LDAP, true, "ldap.corp.test", 636, true, false, "cn=other", "",
                "dc=corp", "(objectClass=person)", "entryUUID"))).isInstanceOf(BadRequestException.class);
    }

    /** Repointing is legitimate when the password comes with it — the rule is re-supply, not refuse. */
    @Test
    void repointingWithAFreshPasswordIsAllowed() {
        DirectoryConnector existing = stored();
        when(connectors.findByOrgIdAndName(ORG, "corp")).thenReturn(Optional.of(existing));

        service.save(new DirectoryConnectorSpec("corp", "Corp LDAP", DirectoryConnectorKind.LDAP, true,
                "ldap.moved.test", 636, true, false, "cn=svc", "fresh", "dc=corp", "(objectClass=person)",
                "entryUUID"));

        verify(cipher).encrypt("fresh");
        assertThat(existing.getHost()).isEqualTo("ldap.moved.test");
    }

    /** A NEW connector with a bind DN and no password would bind anonymously by accident. */
    @Test
    void aNewConnectorThatBindsMustBringAPassword() {
        assertThatThrownBy(() -> service.save(spec("", 636, true, false)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anAnonymousBindNeedsNoPassword() {
        DirectoryConnectorSpec anonymous = new DirectoryConnectorSpec("corp", "Corp", DirectoryConnectorKind.LDAP,
                true, "ldap.corp.test", 636, true, false, null, null, "dc=corp", "(objectClass=person)",
                "entryUUID");

        service.save(anonymous);

        verify(connectors).save(any());
        verify(cipher, never()).encrypt(any());
    }

    /** The stored view is a separate record — it structurally cannot carry the ciphertext, let alone the secret. */
    @Test
    void theViewCannotCarryTheSecret() {
        DirectoryConnector existing = stored();
        when(connectors.findByOrgIdAndName(ORG, "corp")).thenReturn(Optional.of(existing));

        assertThat(service.get("corp").toString())
                .doesNotContain("encg:stored")
                .doesNotContain("s3cret");
    }

    // --- tier scoping ------------------------------------------------------------------------------------

    @Test
    void aBoundButOrglessCallerMayNotWrite() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(spec("s3cret", 636, true, false)))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * A connector correlates entries by external_id within an organization, and that lookup returns nothing
     * when there is no organization — so a platform-tier connector could never match anybody. It would still
     * bind to a remote directory and pull a page of people's data to do nothing with it, and it now has no
     * profile to describe what it provides. Refuse it rather than keep a path that only fails quietly.
     */
    @Test
    void aPlatformTierConnectorIsRefused() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(spec("s3cret", 636, true, false)))
                .isInstanceOf(BadRequestException.class);
        verify(connectors, never()).save(any());
    }

    // --- who can fill an attribute ------------------------------------------------------------------------



    @Test
    void readsOnlyTheActingTiersConnectors() {
        service.list();

        verify(connectors).findByOrgIdOrderByName(ORG);
        verify(connectors, never()).findByOrgIdIsNullOrderByName();
    }
}
