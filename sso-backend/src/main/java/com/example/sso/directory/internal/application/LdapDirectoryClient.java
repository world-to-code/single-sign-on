package com.example.sso.directory.internal.application;

import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import com.unboundid.util.ssl.SSLUtil;
import java.time.Duration;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLSocketFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Reads people out of an LDAP directory.
 *
 * <p>Transport is the whole security story here, because a bind sends the directory credential over the wire.
 * Three rules, none of which the SSRF validator covers:
 * <ul>
 *   <li><b>Never cleartext.</b> Either implicit TLS (LDAPS) or StartTLS before the bind. A connector that asks
 *       for neither is refused rather than silently downgraded — the schema forbids it too.</li>
 *   <li><b>Verify the hostname.</b> A TLS socket that trusts any certificate for any name is not authentication
 *       of the server, and UnboundID does not verify by default. This is why the SDK was chosen over a JNDI
 *       environment map, where the setting is easy to omit and invisible when it is.</li>
 *   <li><b>Bound the wait.</b> Connect and response timeouts, so an unreachable directory occupies a sync
 *       thread for a known interval rather than indefinitely.</li>
 * </ul>
 *
 * <p>The host is SSRF-validated immediately before connecting, not only when the connector was saved — the
 * stored value can be repointed, and DNS can change under a name that validated yesterday.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class LdapDirectoryClient implements DirectoryClient {

    private final OutboundHostValidator hostValidator;

    @Value("${sso.directory.ldap.connect-timeout:PT10S}")
    private Duration connectTimeout;

    @Value("${sso.directory.ldap.response-timeout:PT30S}")
    private Duration responseTimeout;

    @Value("${sso.directory.ldap.page-size:500}")
    private int pageSize;

    /** An overall ceiling: the directory is a remote host, and an unbounded result set is unbounded heap. */
    @Value("${sso.directory.ldap.max-entries:10000}")
    private int maxEntries;

    /**
     * Every person the connector's filter matches, keyed by the identifier we correlate on. Entries without
     * that identifier are dropped here rather than downstream: without it there is nothing to correlate, and
     * guessing by name is the mistake this whole feature exists to avoid.
     */
    @Override
    public DirectoryConnectorKind kind() {
        return DirectoryConnectorKind.LDAP;
    }

    @Override
    public List<DirectoryEntry> readUsers(DirectoryConnector connector, String bindPassword,
            Collection<String> attributes) {
        hostValidator.validate(connector.getHost()); // re-validate at connect time, not only at save time
        List<String> requested = new ArrayList<>(attributes);
        requested.add(connector.getExternalIdAttribute());

        try (LDAPConnection connection = connect(connector, bindPassword)) {
            List<DirectoryEntry> collected = new ArrayList<>();
            ASN1OctetString cookie = null;
            do {
                SearchRequest request = new SearchRequest(connector.getBaseDn(), SearchScope.SUB,
                        connector.getUserFilter(), requested.toArray(String[]::new));
                request.setTimeLimitSeconds((int) responseTimeout.toSeconds());
                // Real paging, not a size limit: a server-side size limit makes the search FAIL once the
                // directory outgrows it, which would silently stop maintaining every attribute-driven
                // membership in a tenant from the day it passed the threshold.
                request.addControl(new SimplePagedResultsControl(pageSize, cookie, true));
                SearchResult result = connection.search(request);
                result.getSearchEntries().stream()
                        .map(entry -> toEntry(entry, connector.getExternalIdAttribute(), attributes))
                        .filter(entry -> StringUtils.hasText(entry.externalId()))
                        .forEach(collected::add);
                if (collected.size() > maxEntries) {
                    log.warn("Directory search for connector {} exceeded the {}-entry ceiling",
                            connector.getName(), maxEntries);
                    throw BadRequestException.of("directory.search.tooManyEntries");
                }
                SimplePagedResultsControl page = SimplePagedResultsControl.get(result);
                cookie = page == null ? null : page.getCookie();
            } while (cookie != null && cookie.getValueLength() > 0);
            return List.copyOf(collected);
        } catch (LDAPException e) {
            // The upstream's own message can carry DNs and filter fragments; keep it out of the caller's face
            // and out of anything that renders. The detail goes to the run record via the caller.
            log.warn("Directory search failed for connector {}: {}", connector.getName(), e.getResultCode());
            throw BadRequestException.of("directory.search.failed");
        }
    }

    /** Opens a bound, TLS-protected connection — or fails. There is no cleartext path. */
    private LDAPConnection connect(DirectoryConnector connector, String bindPassword) throws LDAPException {
        if (!connector.isUseSsl() && !connector.isStartTls()) {
            throw BadRequestException.of("directory.connection.tlsRequired");
        }
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis((int) connectTimeout.toMillis());
        options.setResponseTimeoutMillis(responseTimeout.toMillis());
        // The name on the certificate must be the name we dialled; without this a valid certificate for ANY
        // host would satisfy the handshake.
        options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));

        LDAPConnection connection;
        try {
            SSLUtil sslUtil = sslUtil();
            connection = connector.isUseSsl()
                    ? new LDAPConnection(sslUtil.createSSLSocketFactory(), options, connector.getHost(),
                            connector.getPort())
                    : startTls(options, connector, sslUtil);
        } catch (GeneralSecurityException e) {
            throw BadRequestException.of("directory.connection.tlsFailed");
        }
        if (StringUtils.hasText(connector.getBindDn())) {
            connection.bind(connector.getBindDn(), bindPassword);
        }
        return connection;
    }

    /**
     * The TLS trust anchors. JVM defaults, so a private CA is an operator concern rather than a per-connector
     * setting nobody would audit. Package-private and overridable ONLY so a test can point at an in-memory
     * directory's self-signed certificate — the same seam {@code IdTokenVerifier.decoderFor} uses. Production
     * never substitutes it, and no configuration can: there is no property here to set.
     */
    SSLUtil sslUtil() {
        return new SSLUtil();
    }

    /** Opens on 389 and upgrades BEFORE binding, so the credential never crosses a cleartext socket. */
    private LDAPConnection startTls(LDAPConnectionOptions options, DirectoryConnector connector, SSLUtil sslUtil)
            throws LDAPException, GeneralSecurityException {
        LDAPConnection connection = new LDAPConnection(options, connector.getHost(), connector.getPort());
        SSLSocketFactory factory = sslUtil.createSSLSocketFactory();
        connection.processExtendedOperation(
                new StartTLSExtendedRequest(factory));
        return connection;
    }

    private DirectoryEntry toEntry(SearchResultEntry entry, String externalIdAttribute,
            Collection<String> attributes) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String attribute : attributes) {
            String[] read = entry.getAttributeValues(attribute);
            if (read != null && read.length > 0) {
                values.put(attribute, List.of(read));
            }
        }
        return new DirectoryEntry(entry.getAttributeValue(externalIdAttribute), values);
    }
}
