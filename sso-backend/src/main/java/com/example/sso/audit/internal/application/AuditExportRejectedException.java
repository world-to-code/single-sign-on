package com.example.sso.audit.internal.application;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;

/**
 * The collector did not acknowledge a batch.
 *
 * <p>It exists to carry the STATUS and nothing else. The default handler builds its message from the response
 * body with no length limit, and a schema-validating collector answers a rejected batch by echoing the records
 * it refused — so the default message is a copy of the batch, actor emails and client addresses included,
 * landing in an application log that has neither the access control nor the retention of the audit trail it
 * came from. The status is what an operator needs; the body is what nobody asked to duplicate.
 */
class AuditExportRejectedException extends RestClientException {

    AuditExportRejectedException(HttpStatusCode status) {
        super("The audit-export collector did not acknowledge the batch: HTTP " + status.value());
    }
}
