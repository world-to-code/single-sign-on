package com.example.sso.audit;

import com.example.sso.audit.export.AuditExportSettings;
import com.example.sso.audit.export.AuditExportSettingsService;
import com.example.sso.audit.export.AuditExportTarget;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where the audit trail is allowed to be shipped, and what the credential for it is allowed to look like on
 * disk.
 *
 * <p>The payload here is the complete security history of every tenant, with the collector's credential
 * riding along, so the destination is not an ordinary setting. Each dimension is asserted separately: a test
 * named "rejects a bad URL" passes whether or not the SCHEME is checked, and the scheme is the one that
 * decides whether the whole trail crosses the network in the clear.
 *
 * <p>The accepted host is a PUBLIC one that really resolves, because the SSRF guard is fail-closed on an
 * unresolvable name — an {@code example.com} fixture would be refused for the wrong reason here and would
 * make the positive cases pass or fail on whether this machine has DNS.
 */
class AuditExportSettingsIT extends AbstractIntegrationTest {

    private static final String CREDENTIAL = "collector-bearer-value";

    @Autowired
    AuditExportSettingsService settings;

    @AfterEach
    void cleanup() {
        ownerJdbc().update("delete from audit_export_settings");
    }

    @Test
    void savingAnHttpsCollectorMakesItTheTarget() {
        settings.save(new AuditExportSettings("https://one.one.one.one/ingest", CREDENTIAL, true, false));

        AuditExportTarget target = settings.target().orElseThrow();
        assertThat(target.endpointUrl()).isEqualTo("https://one.one.one.one/ingest");
        assertThat(target.credential()).isEqualTo(CREDENTIAL);
    }

    /**
     * The whole trail, plus the credential that authenticates us to the collector, would cross the network in
     * the clear. A host check is NOT a scheme check — this is asserted on its own for that reason.
     */
    @Test
    void aPlaintextCollectorIsRefused() {
        assertThatThrownBy(() -> settings.save(
                new AuditExportSettings("http://one.one.one.one/ingest", CREDENTIAL, true, false)))
                .isInstanceOf(BadRequestException.class);
        assertThat(settings.target()).isEmpty();
    }

    @Test
    void aNonHttpSchemeIsRefused() {
        for (String url : new String[] {"file:///etc/passwd", "gopher://one.one.one.one/ingest", "ftp://one.one.one.one/x"}) {
            assertThatThrownBy(() -> settings.save(new AuditExportSettings(url, CREDENTIAL, true, false)))
                    .as(url).isInstanceOf(BadRequestException.class);
        }
    }

    /**
     * The SSRF dimension, distinct from the scheme. The collector URL is administrator-set, which makes this a
     * PRIVILEGED SSRF primitive rather than a user-input one — and the cloud metadata address is the target
     * that turns "ship logs somewhere" into "read this instance's credentials".
     */
    @Test
    void aCollectorPointedAtTheInternalNetworkIsRefused() {
        for (String host : new String[] {"127.0.0.1", "localhost", "169.254.169.254", "10.0.0.5"}) {
            assertThatThrownBy(() -> settings.save(
                    new AuditExportSettings("https://" + host + "/ingest", CREDENTIAL, true, false)))
                    .as(host).isInstanceOf(BadRequestException.class);
        }
    }

    /** The credential is a secret: the row must not carry it in the clear, whatever the service returns. */
    @Test
    void theCredentialIsEncryptedAtRest() {
        settings.save(new AuditExportSettings("https://one.one.one.one/ingest", CREDENTIAL, true, false));

        String stored = ownerJdbc().queryForObject(
                "select credential_encrypted from audit_export_settings", String.class);
        assertThat(stored).isNotBlank().doesNotContain(CREDENTIAL);
        assertThat(settings.target().orElseThrow().credential())
                .as("and it still round-trips").isEqualTo(CREDENTIAL);
    }

    /** Configured is not switched on. A disabled collector must not be handed to the exporter as a target. */
    @Test
    void aDisabledCollectorIsNotATarget() {
        settings.save(new AuditExportSettings("https://one.one.one.one/ingest", CREDENTIAL, false, false));

        assertThat(settings.target()).isEmpty();
    }

    @Test
    void nothingConfiguredIsNotATarget() {
        assertThat(settings.target()).isEmpty();
    }

    /** One collector for the deployment: saving again REPLACES it rather than leaving two destinations. */
    @Test
    void savingASecondTimeReplacesTheFirst() {
        settings.save(new AuditExportSettings("https://one.one.one.one/first", CREDENTIAL, true, false));
        settings.save(new AuditExportSettings("https://one.one.one.one/second", "other", true, false));

        assertThat(ownerJdbc().queryForObject("select count(*) from audit_export_settings", Integer.class))
                .isEqualTo(1);
        assertThat(settings.target().orElseThrow().endpointUrl()).isEqualTo("https://one.one.one.one/second");
    }

    /**
     * Re-validated at USE, not only when written. Configuration outlives the check that admitted it: a host
     * that resolved publicly at write time can be repointed at the internal network afterwards, and the row
     * itself can be edited by anything with database access.
     *
     * <p>It THROWS rather than answering empty, and that distinction is the finding this test was rewritten
     * for. Empty is the answer for an export nobody asked for; a configured collector that cannot be honoured
     * is a different fact, and collapsing the two let the export stop with nothing able to notice — the caller
     * cannot raise an alarm about a state it cannot tell apart from "switched off".
     */
    @Test
    void aTargetThatHasBecomeUnsafeSinceItWasSavedIsRefusedLoudly() {
        settings.save(new AuditExportSettings("https://one.one.one.one/ingest", CREDENTIAL, true, false));
        ownerJdbc().update("update audit_export_settings set endpoint_url = ?", "http://127.0.0.1/ingest");

        assertThatThrownBy(() -> settings.target())
                .as("refused at use, so a rewritten row cannot redirect the trail")
                .isInstanceOf(BadRequestException.class);
    }

    /** Switched off is not broken: the one state that must stay quiet, so the alarm above means something. */
    @Test
    void aDisabledExportIsSimplyAbsent() {
        settings.save(new AuditExportSettings("https://one.one.one.one/ingest", CREDENTIAL, false, false));

        assertThat(settings.target()).isEmpty();
    }

    @Test
    void aBlankCredentialIsRefused() {
        assertThatThrownBy(() -> settings.save(
                new AuditExportSettings("https://one.one.one.one/ingest", "  ", true, false)))
                .isInstanceOf(BadRequestException.class);
    }

    /** A legitimate public collector must actually be accepted — a guard that refuses everything is an outage. */
    @Test
    void anOrdinaryPublicCollectorIsAccepted() {
        assertThatCode(() -> settings.save(
                new AuditExportSettings("https://one.one.one.one:8443/api/ingest", CREDENTIAL, true, false)))
                .doesNotThrowAnyException();
    }
}
