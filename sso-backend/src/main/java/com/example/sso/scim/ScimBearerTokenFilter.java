package com.example.sso.scim;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.Roles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates SCIM requests via a {@code Authorization: Bearer <token>} header validated
 * against {@link ScimTokenService}. On success it populates the security context with a SCIM client
 * principal AND binds the token's tenant ({@link OrgContext}) for the request, so SCIM provisioning is
 * RLS-confined to that tenant (a global token binds the platform context); otherwise the request stays
 * unauthenticated and the chain rejects it. The binding is cleared at request end.
 */
@RequiredArgsConstructor
public class ScimBearerTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ScimTokenService tokenService;
    private final OrgContext orgContext;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean bound = false;
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            ScimPrincipal principal = tokenService.authenticate(header.substring(BEARER_PREFIX.length()))
                    .orElse(null);
            if (principal != null) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "scim-client", null, List.of(new SimpleGrantedAuthority(Roles.SCIM))));
                // Bind the token's tenant so every org-scoped read/write in the SCIM request is confined to it.
                if (principal.orgId() == null) {
                    orgContext.enterPlatform();
                } else {
                    orgContext.bindOrg(principal.orgId());
                }
                bound = true;
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (bound) {
                orgContext.clear();
            }
        }
    }
}
