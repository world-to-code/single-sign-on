package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditType;
import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.MappingTargetAuthority;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.EntityKind;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * May this rule still hand out its grant, right now — the zero-trust re-check between a rule being written and
 * a grant being made from it.
 *
 * <p>Two questions, and they are different questions. WHO wrote the rule: a since-demoted or deleted author's
 * rule must stop conferring what they could no longer confer by hand. And WHO decided this user matches: when a
 * condition reads an attribute a DIRECTORY owns, whoever aimed that connector chooses who satisfies the rule
 * without ever needing authority over its target — so {@code directory-connector:write} alone would otherwise
 * be enough to point a connector at a directory you run, assert the value for yourself, and collect whatever an
 * existing and entirely legitimate rule grants.
 *
 * <p>Exposed as ONE {@link #admits} call on purpose. Both materialize paths (per-user and cohort) asked the two
 * checks separately, which is two places for a later one to be added to only one of them — and the cohort path
 * is exactly the one that was left uncovered when the directory check was first written.
 *
 * <p>Fails CLOSED throughout: an unresolvable author yields no authority, and a connector with no recorded
 * configurator vouches for nothing. Every refusal is audited; the caller only sees a boolean.
 */
@Component
@RequiredArgsConstructor
class MappingGrantAdmission {

    private final MappingTargetAuthority targetAuthority;
    private final AttributeDefinitionService definitions;
    private final AttributeSourceAuthority sources;
    private final MappingCohortResolver cohorts;
    private final MappingAuditTrail trail;

    /** Whether a grant from this rule may go ahead. Audits the reason when it may not. */
    boolean admits(MappingRule rule) {
        return authorStillAuthorized(rule) && directorySourcesAuthorized(rule);
    }

    /** Called AFTER a grant that {@link #admits} allowed, so an unattributed rule stays visible for backfill. */
    void noteLegacyAuthor(MappingRule rule) {
        if (rule.getCreatedBy() == null) {
            trail.changedGrantAdmission(AuditType.MAPPING_RULE_LEGACY_AUTHOR, rule);
        }
    }

    /** A rule with no recorded author (legacy/system) is allowed — {@link #noteLegacyAuthor} marks the grant. */
    private boolean authorStillAuthorized(MappingRule rule) {
        if (rule.getCreatedBy() == null) {
            return true;
        }
        if (targetAuthority.authorMayAssign(rule.getCreatedBy(), rule.getThenKind(), rule.getTargetId())) {
            return true;
        }
        trail.changedGrantAdmission(AuditType.MAPPING_RULE_AUTHOR_UNAUTHORIZED, rule);
        return false;
    }

    /** Only privilege-granting targets are gated: a RESOURCE_MEMBER rule confers no authority. */
    private boolean directorySourcesAuthorized(MappingRule rule) {
        if (rule.getThenKind() == MappingTargetKind.RESOURCE_MEMBER) {
            return true;
        }
        Set<String> directoryKeys = cohorts.conditionsOf(rule.getId()).stream()
                .map(MappingCondition::attrKey)
                .filter(key -> definitions.definitionOf(EntityKind.USER, key)
                        .filter(definition -> !definition.locallyEditable())
                        .isPresent())
                .collect(Collectors.toSet());
        if (directoryKeys.isEmpty()) {
            return true; // no directory decides who matches this rule
        }
        AttributeSourceAuthors authors = sources.authorsFilling(directoryKeys);
        boolean authorized = authors.fullyAttributed()
                && authors.configurators().stream().allMatch(configurator ->
                        targetAuthority.authorMayAssign(configurator, rule.getThenKind(), rule.getTargetId()));
        if (!authorized) {
            trail.changedGrantAdmission(AuditType.MAPPING_RULE_DIRECTORY_SOURCE_UNAUTHORIZED, rule);
        }
        return authorized;
    }
}
