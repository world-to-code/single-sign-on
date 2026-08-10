package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditType;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Refuses to run with an audit kind the collector could not be told about.
 *
 * <p>An unmapped type is the quiet failure this whole export is built to avoid: this IdP would report a
 * healthy export and the collector would receive an event it cannot classify, so a detection rule watching
 * for it matches nothing and NOBODY LOOKS. Failing at startup puts the problem in front of whoever added the
 * type, which is the only moment it is cheap.
 *
 * <p>Deliberately fatal rather than a warning. A log line at boot is exactly what nobody reads.
 */
@Component
class AuditExportStartupCheck {

    @EventListener(ApplicationReadyEvent.class)
    void everyAuditTypeIsExportable() {
        List<AuditType> missing = OcsfMapper.missingMappings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "These audit types have no OCSF mapping and would reach the collector unclassified: "
                            + missing + ". Add them to OcsfMapper.");
        }
    }
}
