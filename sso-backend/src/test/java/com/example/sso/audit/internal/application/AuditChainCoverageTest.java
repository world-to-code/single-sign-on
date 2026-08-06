package com.example.sso.audit.internal.application;

import com.example.sso.audit.internal.domain.AuditEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A column the chain does not commit to is a column an attacker may edit freely, and nothing about adding one
 * would announce that. The digest lists its fields explicitly — deliberately, so the set is reviewable — and
 * this is what stops that list from silently falling behind the table.
 *
 * <p>When it fails, the fix is a DECISION, not a rename: either mirror the new column in
 * {@link AuditRowSnapshot} and bump {@link AuditRowDigest#VERSION} (old rows keep verifying under the old
 * reader), or add it below with the reason it is excluded.
 */
class AuditChainCoverageTest {

    /**
     * The chain's own bookkeeping. These cannot be committed to: the digest is an INPUT to them, so including
     * them would ask a row to commit to a value derived from itself.
     */
    private static final Set<String> CHAIN_BOOKKEEPING =
            Set.of("seq", "rowSalt", "rowHash", "prevHash", "chainHash", "chainVersion");

    @Test
    void everyPersistedColumnIsEitherCommittedToOrDeliberatelyExcluded() {
        Set<String> committed = Arrays.stream(AuditRowSnapshot.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        Set<String> persisted = Arrays.stream(AuditEvent.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(persisted)
                .as("a persisted column the hash chain does not cover can be edited without detection — "
                        + "mirror it in AuditRowSnapshot and bump AuditRowDigest.VERSION, or exclude it here "
                        + "with a reason")
                .allMatch(column -> committed.contains(column) || CHAIN_BOOKKEEPING.contains(column));
    }

    /** The mirror has to stay a mirror: a component naming no column would digest something that is not there. */
    @Test
    void theSnapshotInventsNoFieldTheTableDoesNotHave() {
        Set<String> persisted = Arrays.stream(AuditEvent.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(Arrays.stream(AuditRowSnapshot.class.getRecordComponents()).map(RecordComponent::getName))
                .allMatch(persisted::contains);
    }
}
