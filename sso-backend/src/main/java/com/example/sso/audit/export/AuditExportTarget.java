package com.example.sso.audit.export;

/**
 * A collector the exporter may actually send to right now: enabled, and re-validated at THIS moment.
 *
 * <p>Distinct from {@link AuditExportSettings}, which is what somebody typed. Handing out one type for both
 * would let a caller send to a destination that was acceptable when it was saved and is not any more —
 * configuration outlives the check that admitted it.
 */
public record AuditExportTarget(String endpointUrl, String credential) {
}
