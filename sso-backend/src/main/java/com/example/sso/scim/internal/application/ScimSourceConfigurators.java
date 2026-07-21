package com.example.sso.scim.internal.application;

import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.SourceConfigurators;
import com.example.sso.scim.internal.domain.ScimToken;
import com.example.sso.scim.internal.domain.ScimTokenRepository;
import com.example.sso.tenancy.OrgContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is accountable for what a SCIM client writes: whoever issued the tokens it could be using.
 *
 * <p>A SCIM source has no connector to carry a configurator, which is why nothing could be attributed to it
 * before — the answer was structurally "nobody", so no SCIM-fed attribute could ever grant a role or select a
 * policy. The token is the equivalent artefact: issuing one hands out the ability to write this tenant's
 * attributes, so the issuer is the person who aimed the source.
 *
 * <p>EVERY live token counts, not the one being used — the guards run when an attribute is EVALUATED, long
 * after whichever client wrote it has gone. Requiring all of them to be accountable is the conservative
 * reading, and it matches how the caller uses the answer: each configurator must independently hold the
 * authority the grant would confer.
 *
 * <p>That includes the platform-global tokens. One of those enters the platform context and can provision into
 * any tenant, so a tenant is not accountable for its attributes while an unattributed global token is live —
 * counting only the tenant's own would report a completeness the record does not support.
 */
@Component
@RequiredArgsConstructor
class ScimSourceConfigurators implements SourceConfigurators {

    private final ScimTokenRepository tokens;
    private final OrgContext orgContext;
    private final Clock clock;

    @Override
    public boolean handles(ProfileKind kind) {
        return kind == ProfileKind.SCIM;
    }

    @Override
    @Transactional(readOnly = true)
    public AttributeSourceAuthors configuratorsOf(Collection<UUID> sourceProfileIds) {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null) {
            // No tenant to ask about. Incomplete rather than empty: we did not find that nobody is
            // accountable, we failed to look — and the caller must be able to tell those apart.
            return new AttributeSourceAuthors(Set.of(), false);
        }
        Instant now = clock.instant();
        List<ScimToken> live = tokens.findByOrgIdOrOrgIdIsNull(org).stream()
                .filter(token -> token.isActiveAt(now)).toList();
        if (live.isEmpty()) {
            // A SCIM profile with no live token cannot be writing anything, so there is nothing to vouch for.
            return AttributeSourceAuthors.none();
        }
        Set<UUID> configurators = new HashSet<>();
        boolean complete = true;
        for (ScimToken token : live) {
            if (token.getIssuedBy() == null) {
                complete = false; // a token nobody is on record for cannot vouch for anything
            } else {
                configurators.add(token.getIssuedBy());
            }
        }
        return new AttributeSourceAuthors(Set.copyOf(configurators), complete);
    }
}
