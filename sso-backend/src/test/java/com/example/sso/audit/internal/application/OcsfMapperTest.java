package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.export.AuditExportRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape this IdP publishes to a collector.
 *
 * <p>Once a SIEM parses this, it is a CONTRACT: an internal column rename or a new audit kind must not change
 * what a customer's rules match on. So the mapping is asserted as a contract too, not spot-checked.
 *
 * <p>The completeness check is the load-bearing one. An unmapped {@link AuditType} that quietly exported as
 * "Other" would be invisible from both ends — this IdP would report success and the collector would ingest a
 * shrug — so a new kind must fail at startup instead, where somebody is still looking.
 */
class OcsfMapperTest {

    private final OcsfMapper mapper = new OcsfMapper("Mini SSO");

    /** No audit kind may reach a collector unclassified. */
    @ParameterizedTest
    @EnumSource(AuditType.class)
    void everyAuditTypeHasAnOcsfClassAndActivity(AuditType type) {
        Map<String, Object> event = mapper.toOcsf(record(type));

        assertThat(event.get("class_uid")).as("%s has no OCSF class", type).isNotNull();
        assertThat(event.get("activity_id")).as("%s has no OCSF activity", type).isNotNull();
        assertThat(event.get("type_uid"))
                .as("type_uid is class_uid * 100 + activity_id, which is how OCSF identifies an event")
                .isEqualTo(((Integer) event.get("class_uid")) * 100 + (Integer) event.get("activity_id"));
    }

    /** The startup guard itself: it must actually fire when a type is missing, or it is decoration. */
    @Test
    void anUnmappedTypeIsRefusedRatherThanExportedAsSomethingVague() {
        List<AuditType> unmapped = new ArrayList<>(List.of(AuditType.AUTH_SUCCESS));

        assertThat(OcsfMapper.missingMappings(type -> !unmapped.contains(type)))
                .containsExactly(AuditType.AUTH_SUCCESS);
    }

    @Test
    void aSignInIsAnAuthenticationLogon() {
        Map<String, Object> event = mapper.toOcsf(record(AuditType.AUTH_SUCCESS));

        assertThat(event.get("class_uid")).isEqualTo(3002);   // Authentication
        assertThat(event.get("activity_id")).isEqualTo(1);    // Logon
        assertThat(event.get("status_id")).isEqualTo(1);      // Success
    }

    @Test
    void aFailedSignInCarriesTheFailureStatus() {
        assertThat(mapper.toOcsf(failed(AuditType.AUTH_FAILURE)).get("status_id")).isEqualTo(2);
    }

    /** Holding an account changes what that account may do — Account Change, not an API call. */
    @Test
    void anAccountHoldIsAnAccountChange() {
        assertThat(mapper.toOcsf(record(AuditType.ACCOUNT_HELD)).get("class_uid")).isEqualTo(3001);
    }

    /** Granting privileges is Authorize Session, which is what a SIEM's privilege-escalation rules watch. */
    @Test
    void aPermissionChangeIsAnAuthorizeSession() {
        assertThat(mapper.toOcsf(record(AuditType.USER_PERMISSIONS_UPDATED)).get("class_uid")).isEqualTo(3003);
    }

    /** Configuration edits are API Activity — not Account Change, which would pollute account analytics. */
    @Test
    void aConfigurationEditIsApiActivity() {
        assertThat(mapper.toOcsf(record(AuditType.SMTP_SETTINGS_CHANGED)).get("class_uid")).isEqualTo(6003);
    }

    @SuppressWarnings("unchecked")
    @Test
    void theActorIsTheActorAndTheSubjectIsWhatItActedOn() {
        UUID actorId = UUID.randomUUID();
        AuditExportRecord row = new AuditExportRecord(7L, Instant.now(), AuditType.ACCOUNT_HELD.name(), "ADMIN",
                "admin", true, "user=victim", null, "INFO", "USER", actorId, "admin@example.com", "The Admin",
                "USER", "9d1f", "203.0.113.7", "curl/8", "cli", "req-1", UUID.randomUUID());

        Map<String, Object> event = mapper.toOcsf(row);

        Map<String, Object> actor = (Map<String, Object>) event.get("actor");
        Map<String, Object> user = (Map<String, Object>) actor.get("user");
        assertThat(user.get("uid")).isEqualTo(actorId.toString());
        assertThat(user.get("email_addr")).isEqualTo("admin@example.com");
        assertThat(event.get("message")).asString().contains("victim");
    }

    /**
     * An unverified actor is a CLAIM, not an identity — a failed-login username nobody proved control of.
     * A collector that renders it as a user would attribute events to a principal this IdP never
     * authenticated, so the distinction has to survive the trip rather than being flattened here.
     */
    @SuppressWarnings("unchecked")
    @Test
    void anUnprovenPrincipalIsNotPresentedAsAnIdentifiedUser() {
        AuditExportRecord row = new AuditExportRecord(8L, Instant.now(), AuditType.AUTH_FAILURE.name(),
                "AUTHENTICATION", "someone@example.com", false, null, "auth.failed", "WARNING",
                "ANONYMOUS", null, null, null, "NONE", null, "203.0.113.7", null, null, null, null);

        Map<String, Object> event = mapper.toOcsf(row);

        Map<String, Object> actor = (Map<String, Object>) event.get("actor");
        Map<String, Object> user = (Map<String, Object>) actor.get("user");
        assertThat(user.get("uid")).as("no account id, because none was resolved").isNull();
        assertThat(user.get("type_id")).as("OCSF Unknown, not User").isEqualTo(0);
        assertThat(user.get("name")).as("the claimed name still travels, as a name").isEqualTo("someone@example.com");
    }

    /**
     * The tenant. OCSF has no home for it, and this is the single most important note for whoever configures
     * the collector: a multi-tenant IdP shipping into one index means a query with no tenant predicate
     * crosses tenants.
     */
    @SuppressWarnings("unchecked")
    @Test
    void theTenantTravelsAsAnExplicitLabel() {
        UUID org = UUID.randomUUID();
        Map<String, Object> event = mapper.toOcsf(recordInOrg(AuditType.AUTH_SUCCESS, org));

        Map<String, Object> metadata = (Map<String, Object>) event.get("metadata");
        Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");
        assertThat(labels.get("tenant_id")).isEqualTo(org.toString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aPlatformEventSaysSoRatherThanOmittingTheTenant() {
        Map<String, Object> event = mapper.toOcsf(recordInOrg(AuditType.SIGNING_KEY_ROTATED, null));

        Map<String, Object> metadata = (Map<String, Object>) event.get("metadata");
        Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");
        assertThat(labels.get("tenant_id"))
                .as("absent would be ambiguous with 'we forgot to send it'")
                .isEqualTo("platform");
    }

    /** The dedup key the collector needs, because a retried delivery repeats a batch. */
    @SuppressWarnings("unchecked")
    @Test
    void theRecordIdIsTheDedupKey() {
        Map<String, Object> metadata = (Map<String, Object>) mapper.toOcsf(record(AuditType.AUTH_SUCCESS))
                .get("metadata");

        assertThat(metadata.get("uid")).isEqualTo("42");
    }

    @Test
    void severityMapsToTheOcsfScale() {
        assertThat(mapper.toOcsf(withSeverity("INFO")).get("severity_id")).isEqualTo(1);
        assertThat(mapper.toOcsf(withSeverity("WARNING")).get("severity_id")).isEqualTo(3);
        assertThat(mapper.toOcsf(withSeverity("CRITICAL")).get("severity_id")).isEqualTo(5);
    }

    /**
     * A server error's detail is an exception message plus our own stack frames. The message is uncontrolled —
     * a unique-violation quotes the value that collided, routinely somebody's address — and the frames map
     * this system's internals for a third party. It was written to be read through the admin API; the export
     * is a different audience.
     */
    @Test
    void aServerErrorsInternalDetailDoesNotLeaveTheSystem() {
        AuditExportRecord row = new AuditExportRecord(9L, Instant.now(), AuditType.SERVER_ERROR.name(), "SYSTEM",
                "anonymous", false,
                "POST /api/users [ref-1] org.postgresql.util.PSQLException: Key (email)=(victim@example.com)"
                        + " already exists\n  at UserService.create(UserService.java:42)",
                "PSQLException", "CRITICAL", "ANONYMOUS", null, null, null, "NONE", null,
                "203.0.113.7", null, null, "ref-1", null);

        Map<String, Object> event = mapper.toOcsf(row);

        assertThat(event.get("message")).isNull();
        assertThat(event.get("status_detail"))
                .as("the exception TYPE is ours to publish, and is what a collector correlates on")
                .isEqualTo("PSQLException");
    }

    /** And every other kind still carries its detail, or the export loses the thing it exists to ship. */
    @Test
    void anOrdinaryEventStillCarriesItsDetail() {
        assertThat(mapper.toOcsf(record(AuditType.USER_CREATED)).get("message")).isEqualTo("detail");
    }

    private AuditExportRecord record(AuditType type) {
        return recordInOrg(type, UUID.randomUUID());
    }

    private AuditExportRecord recordInOrg(AuditType type, UUID orgId) {
        return new AuditExportRecord(42L, Instant.now(), type.name(), type.getCategory().name(), "admin", true,
                "detail", null, "INFO", "USER", UUID.randomUUID(), "a@example.com", "A", "NONE", null,
                "203.0.113.7", null, null, null, orgId);
    }

    private AuditExportRecord failed(AuditType type) {
        AuditExportRecord ok = record(type);
        return new AuditExportRecord(ok.id(), ok.occurredAt(), ok.type(), ok.category(), ok.principal(), false,
                ok.detail(), ok.reason(), "WARNING", ok.actorType(), ok.actorId(), ok.actorEmail(),
                ok.actorDisplay(), ok.subjectType(), ok.subjectId(), ok.remoteIp(), ok.userAgent(), ok.device(),
                ok.requestId(), ok.orgId());
    }

    private AuditExportRecord withSeverity(String severity) {
        AuditExportRecord ok = record(AuditType.AUTH_SUCCESS);
        return new AuditExportRecord(ok.id(), ok.occurredAt(), ok.type(), ok.category(), ok.principal(), true,
                ok.detail(), ok.reason(), severity, ok.actorType(), ok.actorId(), ok.actorEmail(),
                ok.actorDisplay(), ok.subjectType(), ok.subjectId(), ok.remoteIp(), ok.userAgent(), ok.device(),
                ok.requestId(), ok.orgId());
    }
}
