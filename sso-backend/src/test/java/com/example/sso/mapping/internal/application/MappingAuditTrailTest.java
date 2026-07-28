package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The one decision this type exists to hold: which audit rows survive a rollback.
 *
 * <p>Both answers are correct for exactly one kind of row and wrong for the other, and neither is visible
 * until an investigator reads the trail — a change row that outlived its rollback asserts an authority move
 * that never happened, and a refusal that did NOT outlive it leaves a rule silently not converging. Asserted
 * by MECHANISM rather than by outcome, because the mechanism is the whole difference: deferred publication
 * versus an inline write on its own transaction.
 */
@ExtendWith(MockitoExtension.class)
class MappingAuditTrailTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock private ApplicationEventPublisher events;
    @Mock private AuditService audit;

    @InjectMocks private MappingAuditTrail trail;

    @Test
    void aMembershipChangeIsPublishedForAfterCommitAndNeverWrittenInline() {
        trail.changedMembership(AuditType.MAPPING_RULE_RETRACTED, rule(), USER);

        assertThat(published().type()).isEqualTo(AuditType.MAPPING_RULE_RETRACTED);
        verify(audit, never()).record(any()); // inline, it would commit in its own transaction and outlive a rollback
    }

    @Test
    void aGrantAdmissionOutcomeIsPublishedTheSameWay() {
        trail.changedGrantAdmission(AuditType.MAPPING_RULE_AUTHOR_UNAUTHORIZED, rule());

        assertThat(published().type()).isEqualTo(AuditType.MAPPING_RULE_AUTHOR_UNAUTHORIZED);
        verify(audit, never()).record(any());
    }

    /** The opposite polarity: the transaction is about to roll back, so deferring would lose the row entirely. */
    @Test
    void aRefusalIsWrittenInlineWithTheReasonAndTheTier() {
        trail.refusalNow(ORG, 3, new IllegalStateException("admin.lastAdmin"));

        ArgumentCaptor<AuditRecord> written = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(written.capture());
        assertThat(written.getValue().type()).isEqualTo(AuditType.MAPPING_RULE_RETRACTION_REFUSED);
        assertThat(written.getValue().success()).isFalse();
        assertThat(written.getValue().reason()).isEqualTo("admin.lastAdmin");
        assertThat(written.getValue().orgId()).isEqualTo(ORG);
        verify(events, never()).publishEvent(any(Object.class)); // deferred, it would go down with the rollback
    }

    private AuditRecord published() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(MappingAuditPending.class);
        return ((MappingAuditPending) event.getValue()).record();
    }

    private MappingRule rule() {
        MappingRule rule = MappingRule.of(MappingTargetKind.ROLE, UUID.randomUUID(), ORG, UUID.randomUUID());
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
        return rule;
    }
}
