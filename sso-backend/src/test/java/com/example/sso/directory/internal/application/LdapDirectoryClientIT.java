package com.example.sso.directory.internal.application;

import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import com.unboundid.util.ssl.cert.ManageCertificates;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * The client against a REAL LDAP server rather than a mock — the reason UnboundID was chosen over a JNDI
 * environment map. A mock cannot tell us whether the filter, the scope, the attribute request or the
 * identifier extraction actually work against a directory; only a round trip can.
 *
 * <p>The listener speaks StartTLS, because the client refuses to bind over cleartext — a plain listener could
 * not reach the search path at all, which is the rule working rather than an obstacle to remove. The only
 * concession is the trust anchors: the test trusts the server's throwaway self-signed certificate, while the
 * transport rules themselves run exactly as they do in production.
 */
class LdapDirectoryClientIT {

    private static final String BASE = "dc=corp,dc=test";

    private InMemoryDirectoryServer server;
    private LdapDirectoryClient client;
    private OutboundHostValidator hostValidator;

    @BeforeEach
    void startDirectory() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.setSchema(null); // accept the attributes the fixtures use without a full schema definition
        config.addAdditionalBindCredentials("cn=svc," + BASE, "s3cret");
        // A StartTLS-capable listener: the client refuses to bind over cleartext, so a plain listener could
        // not exercise the search path at all — which is the rule working, not a test obstacle to remove.
        SSLUtil serverSsl = new SSLUtil(selfSignedKeyManager(), new TrustAllTrustManager());
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig(
                "test", null, 0, serverSsl.createSSLSocketFactory()));
        server = new InMemoryDirectoryServer(config);
        server.startListening();
        server.add("dn: " + BASE, "objectClass: top", "objectClass: domain", "dc: corp");
        server.add("dn: uid=ada," + BASE, "objectClass: top", "objectClass: person",
                "cn: Ada", "sn: Lovelace", "uid: ada", "employeeNumber: dir-ada", "department: Engineering",
                "l: London");
        server.add("dn: uid=grace," + BASE, "objectClass: top", "objectClass: person",
                "cn: Grace", "sn: Hopper", "uid: grace", "employeeNumber: dir-grace", "department: Research");
        // Nothing to correlate on: no employeeNumber.
        server.add("dn: uid=anon," + BASE, "objectClass: top", "objectClass: person",
                "cn: Anon", "sn: Nobody", "uid: anon", "department: Ghost");

        hostValidator = mock(OutboundHostValidator.class);
        // Trust the in-memory server's self-signed certificate. Overriding the trust anchors is the ONLY
        // concession the test makes — the transport rules themselves (no cleartext, verify the hostname) run
        // exactly as they do in production.
        client = new LdapDirectoryClient(hostValidator) {
            @Override
            SSLUtil sslUtil() {
                return new SSLUtil(new TrustAllTrustManager());
            }
        };
        ReflectionTestUtils.setField(client, "connectTimeout", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(client, "responseTimeout", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(client, "pageSize", 500);
        ReflectionTestUtils.setField(client, "maxEntries", 10000);
    }

    /** A throwaway self-signed certificate so the listener can speak StartTLS at all. */
    private KeyStoreKeyManager selfSignedKeyManager() throws Exception {
        File keyStore = File.createTempFile("ldap-test-", ".jks");
        keyStore.deleteOnExit();
        keyStore.delete(); // the tool wants to create it
        ManageCertificates.main(null, System.out, System.err,
                "generate-self-signed-certificate",
                "--keystore", keyStore.getAbsolutePath(),
                "--keystore-password", "changeit",
                "--alias", "server",
                "--subject-dn", "CN=localhost");
        return new KeyStoreKeyManager(keyStore.getAbsolutePath(), "changeit".toCharArray(), "JKS", "server");
    }

    @AfterEach
    void stopDirectory() {
        if (server != null) {
            server.shutDown(true);
        }
    }

    /** Plaintext for the search tests only — the transport rules have their own cases below. */
    private DirectoryConnector connector(String filter) {
        DirectoryConnector connector = DirectoryConnector.create(UUID.randomUUID(), "corp",
                DirectoryConnectorKind.LDAP);
        connector.reconfigure("Corp", true, "localhost", server.getListenPort(), false, true,
                "cn=svc," + BASE, "encg:x", BASE, filter, "employeeNumber");
        return connector;
    }

    @Test
    void readsTheMappedAttributesOfEveryMatchingPerson() {
        List<DirectoryEntry> entries = client.readUsers(connector("(objectClass=person)"), "s3cret",
                List.of("department", "l"));

        assertThat(entries).extracting(DirectoryEntry::externalId)
                .containsExactlyInAnyOrder("dir-ada", "dir-grace");
        DirectoryEntry ada = entries.stream().filter(e -> e.externalId().equals("dir-ada")).findFirst()
                .orElseThrow();
        assertThat(ada.attributes().get("department")).containsExactly("Engineering");
        assertThat(ada.attributes().get("l")).containsExactly("London");
    }

    /**
     * An entry with no stable identifier is dropped rather than passed on. Without it there is nothing to
     * correlate, and falling back to a name or an address is exactly the mistake this design avoids.
     */
    @Test
    void dropsAnEntryWithNothingToCorrelateOn() {
        List<DirectoryEntry> entries = client.readUsers(connector("(objectClass=person)"), "s3cret",
                List.of("department"));

        assertThat(entries).extracting(DirectoryEntry::externalId).doesNotContain((String) null);
        assertThat(entries).hasSize(2); // the third person carries no correlation identifier
    }

    @Test
    void honoursTheConnectorsFilter() {
        List<DirectoryEntry> entries = client.readUsers(connector("(uid=ada)"), "s3cret", List.of("department"));

        assertThat(entries).extracting(DirectoryEntry::externalId).containsExactly("dir-ada");
    }

    /** Only what was mapped is requested — a directory read is not a licence to pull every attribute. */
    @Test
    void asksOnlyForTheAttributesItWasGiven() {
        List<DirectoryEntry> entries = client.readUsers(connector("(uid=ada)"), "s3cret", List.of("department"));

        assertThat(entries.getFirst().attributes()).containsOnlyKeys("department");
    }

    /**
     * The case that used to break silently. A server-side size limit makes the search FAIL once a directory
     * grows past it, so a tenant crossing the threshold would stop having its attribute-driven memberships
     * maintained — forever, and with an error that named no cause. Paging has to walk the whole result set.
     */
    @Test
    void readsEveryPersonEvenWhenTheDirectoryOutgrowsOnePage() throws Exception {
        for (int i = 0; i < 25; i++) {
            server.add("dn: uid=bulk" + i + "," + BASE, "objectClass: top", "objectClass: person",
                    "cn: Bulk" + i, "sn: Person", "uid: bulk" + i, "employeeNumber: dir-bulk-" + i,
                    "department: Bulk");
        }
        ReflectionTestUtils.setField(client, "pageSize", 5); // five pages plus the two fixtures above

        List<DirectoryEntry> entries = client.readUsers(connector("(objectClass=person)"), "s3cret",
                List.of("department"));

        assertThat(entries).hasSize(27);
        assertThat(entries).extracting(DirectoryEntry::externalId).contains("dir-bulk-0", "dir-bulk-24");
    }

    /** A remote host must not be able to push an unbounded result set into our heap. */
    @Test
    void refusesToReadPastTheConfiguredCeiling() throws Exception {
        for (int i = 0; i < 25; i++) {
            server.add("dn: uid=bulk" + i + "," + BASE, "objectClass: top", "objectClass: person",
                    "cn: Bulk" + i, "sn: Person", "uid: bulk" + i, "employeeNumber: dir-bulk-" + i,
                    "department: Bulk");
        }
        ReflectionTestUtils.setField(client, "pageSize", 5);
        ReflectionTestUtils.setField(client, "maxEntries", 10);

        assertThatThrownBy(() -> client.readUsers(connector("(objectClass=person)"), "s3cret",
                List.of("department"))).isInstanceOf(BadRequestException.class);
    }

    // --- transport ---------------------------------------------------------------------------------------

    /**
     * A bind sends the directory credential over the wire, so there is no cleartext path: either implicit TLS
     * or StartTLS before the bind. The schema forbids it too; this is the code refusing to be talked into it.
     */
    @Test
    void refusesAConnectionThatWouldSendTheBindCredentialInTheClear() {
        DirectoryConnector cleartext = connector("(objectClass=person)");
        cleartext.reconfigure("Corp", true, "localhost", server.getListenPort(), false, false,
                "cn=svc," + BASE, "encg:x", BASE, "(objectClass=person)", "employeeNumber");

        assertThatThrownBy(() -> client.readUsers(cleartext, "s3cret", List.of("department")))
                .isInstanceOf(BadRequestException.class);
    }

    /** The stored host can be repointed after it was saved, so it is re-validated immediately before dialling. */
    @Test
    void revalidatesTheHostAtConnectTimeNotOnlyAtSaveTime() {
        doThrow(new BadRequestException("internal address")).when(hostValidator).validate("localhost");

        assertThatThrownBy(() ->
                client.readUsers(connector("(objectClass=person)"), "s3cret", List.of("department")))
                .isInstanceOf(BadRequestException.class);
    }

    /** An upstream failure must not surface DNs or filter fragments to whoever triggered the sync. */
    @Test
    void aFailedSearchDoesNotLeakTheDirectorysOwnMessage() {
        DirectoryConnector badBase = connector("(objectClass=person)");
        badBase.reconfigure("Corp", true, "localhost", server.getListenPort(), false, true,
                "cn=svc," + BASE, "encg:x", "dc=nonexistent", "(objectClass=person)", "employeeNumber");

        assertThatThrownBy(() -> client.readUsers(badBase, "s3cret", List.of("department")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageNotContaining("dc=nonexistent");
    }
}
