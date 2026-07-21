package com.example.sso.portal.internal.catalog.application;

import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeSourceConfigurationChangedEvent;
import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.EntityKind;
import com.example.sso.tenancy.OrgContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Whether the identity sources that can fill a set of attribute keys are attributable to an administrator.
 *
 * <p>This is a property of the tenant's configuration — which connectors exist and what they are mapped to —
 * not of the user being resolved. It nonetheless sits on the path that decides a live session's policy, and
 * was being recomputed once per binding per request: for a three-condition binding, roughly ten queries
 * against the ABAC hot table on every authenticated request. Caching is what makes the check affordable here.
 *
 * <p>The cache is also what makes the negative verdict sayable. A binding whose sources cannot be attributed
 * stops matching, so the user falls back to the organization's default policy — which may be the LOOSER of the
 * two — while the binding still reads as enabled in the console. One log line per request is not an option on
 * this path; one per key set per TTL is.
 *
 * <p>Staleness is bounded by the TTL rather than by eviction on change: the inputs live in three modules
 * (definitions, mappings, connectors) and a verdict that is at most a TTL out of date is the right trade for
 * not wiring three more event listeners into the session path.
 */
@Component
@Slf4j
class AttributeSourceProvenance {

    private final AttributeDefinitionService definitions;
    private final AttributeSourceAuthority sources;
    private final OrgContext orgContext;
    private final Cache<VerdictKey, Boolean> verdicts;

    AttributeSourceProvenance(AttributeDefinitionService definitions, AttributeSourceAuthority sources,
            OrgContext orgContext,
            @Value("${sso.portal.attribute-source-verdict-ttl}") Duration ttl,
            @Value("${sso.portal.attribute-source-verdict-cache-size}") long maxSize) {
        this.definitions = definitions;
        this.sources = sources;
        this.orgContext = orgContext;
        this.verdicts = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    }

    /**
     * Whether every source that can fill these keys is accounted for.
     *
     * <p>Keys an administrator owns need no vouching — nobody else can write them — so a condition set with no
     * source-owned key is accounted for without asking.
     */
    boolean accountedFor(Collection<String> conditionKeys) {
        // The organization is part of the key: attribute keys are tenant-chosen names, so two tenants naming a
        // key "clearance" is the expected case. Caching on the keys alone would let one tenant's connector
        // decide another tenant's policy. A caller bound to no organization shares nothing with one that is.
        VerdictKey key = new VerdictKey(orgContext.currentOrg().orElse(null), Set.copyOf(conditionKeys));
        return verdicts.get(key, this::compute);
    }

    private boolean compute(VerdictKey key) {
        Set<String> sourced = key.conditionKeys().stream()
                .filter(this::filledBySource)
                .collect(Collectors.toSet());
        if (sourced.isEmpty()) {
            return true;
        }
        boolean accounted = sources.authorsFilling(sourced).fullyAttributed();
        if (!accounted) {
            // Loud on purpose. The binding will not match, the user will resolve to the default policy, and
            // nothing else in the system says so — the console still shows the binding as enabled.
            log.warn("Policy bindings conditioned on {} are not being applied in organization {}: no configured"
                    + " source can be attributed for those attributes", sourced, key.orgId());
        }
        return accounted;
    }

    /**
     * Drop the organization's verdicts the moment its sources change.
     *
     * <p>The TTL stays as a backstop, but it cannot be the whole answer: this verdict decides whether an
     * attribute-conditioned binding applies, so letting a stale positive survive a connector deletion is a
     * revocation that does not propagate. AFTER_COMMIT, because a rolled-back change revoked nothing.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSourceConfigurationChanged(AttributeSourceConfigurationChangedEvent event) {
        verdicts.asMap().keySet().removeIf(key -> Objects.equals(key.orgId(), event.orgId()));
    }

    private boolean filledBySource(String attrKey) {
        return definitions.definitionOf(EntityKind.USER, attrKey)
                .filter(definition -> !definition.locallyEditable())
                .isPresent();
    }
}
