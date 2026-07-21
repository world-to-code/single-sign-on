package com.example.sso.scim.internal.application;

import com.example.sso.scim.ScimPrincipal;
import com.example.sso.scim.ScimTokenService;
import com.example.sso.scim.internal.domain.ScimToken;
import com.example.sso.scim.internal.domain.ScimTokenRepository;
import com.example.sso.user.role.Roles;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link ScimTokenService}. Issues and validates SCIM bearer tokens; the plaintext token is
 * returned only at issuance and only its SHA-256 hash is stored.
 */
@Service
@RequiredArgsConstructor
public class ScimTokenServiceImpl implements ScimTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ScimTokenRepository tokens;
    private final OrgContext orgContext;
    private final UserService users;

    @Override
    @Transactional
    public String issue(String description, Duration ttl) {
        String raw = randomToken();
        Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        // Owned by the acting tenant (bound org), or global when a platform admin issues with none bound.
        // The issuer is recorded because a SCIM client is an identity SOURCE: it can write any attribute the
        // tenant's profile declares, and the provenance guards refuse to let an attribute nobody is accountable
        // for grant a role or select a session policy. Without this a SCIM-fed attribute could never do either.
        tokens.save(new ScimToken(description, hash(raw), expiresAt, orgContext.currentOrg().orElse(null),
                resolveIssuer()));

        return raw;
    }

    @Override
    @Transactional
    public void ensureToken(String rawToken, String description) {
        String hash = hash(rawToken);
        // Check cross-org (like issue/authenticate): the token hash is globally unique, so a seed run in some
        // bound context must still see an existing token owned by another tier and stay idempotent.
        if (!orgContext.callAsPlatform(() -> tokens.existsByTokenHash(hash))) {
            tokens.save(new ScimToken(description, hash, null, null, null)); // a global dev token, nobody to attribute
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ScimPrincipal> authenticate(String rawToken) {
        String hash = hash(rawToken);
        // The request is not yet tenant-bound, so look the token up cross-org (platform); RLS would otherwise
        // hide an org-owned token. The caller binds the returned org for the SCIM request.
        return orgContext.callAsPlatform(() -> tokens.findByTokenHash(hash))
                .filter(token -> token.isActiveAt(Instant.now()))
                .map(token -> new ScimPrincipal(token.getOrgId()));
    }

    /**
     * The administrator behind this request, resolved the way a directory connector resolves its configurator:
     * a platform super-admin is a global account, anyone else is looked up in their own tier. Null when there
     * is no authenticated principal (a seeder or a test), which the guards then treat as unattributed.
     */
    private UUID resolveIssuer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        boolean platformAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Roles.ADMIN::equals);
        return (platformAdmin
                ? users.findByUsernameInOrg(authentication.getName(), null)
                : users.findByUsernameInOrg(authentication.getName(), orgContext.currentOrg().orElse(null)))
                .map(UserAccount::getId).orElse(null);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
