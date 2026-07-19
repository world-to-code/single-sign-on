package com.example.sso.auth.internal.login.api;

import com.example.sso.auth.internal.login.application.FederatedAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browser-facing endpoints of an inbound federated login. {@code /start} redirects to the tenant's upstream
 * OIDC provider; the upstream redirects back to {@code /callback}. Both are top-level browser GET navigations
 * (no CSRF token to present — the OAuth {@code state}, bound in the session, is the callback's CSRF defense),
 * covered by the {@code /api/auth/**} permitAll rule. Because these render browser redirects (not XHR JSON), a
 * failed callback is turned into a redirect back to the SPA with an error flag rather than a JSON error page.
 */
@RestController
@RequestMapping("/api/auth/federation")
@RequiredArgsConstructor
public class FederationController {

    private static final Logger log = LoggerFactory.getLogger(FederationController.class);
    private static final URI SUCCESS = URI.create("/");
    private static final URI FAILURE = URI.create("/?login_error=federation");

    private final FederatedAuthenticationService federatedAuth;

    /** Starts federation: 302 to the upstream authorization endpoint (state/nonce/PKCE stashed server-side). */
    @GetMapping("/{alias}/start")
    public ResponseEntity<Void> start(@PathVariable String alias, HttpServletRequest request) {
        return redirect(URI.create(federatedAuth.start(alias, request)));
    }

    /**
     * The upstream's redirect target. Establishes the session on a valid code, else redirects back to the SPA
     * with an error flag — the upstream may itself return {@code error=access_denied} (consent declined), and a
     * validation failure must not surface as a raw JSON error in the address bar.
     */
    @GetMapping("/{alias}/callback")
    public ResponseEntity<Void> callback(@PathVariable String alias,
            @RequestParam(required = false) String code, @RequestParam(required = false) String state,
            @RequestParam(required = false) String error, HttpServletRequest request, HttpServletResponse response) {
        if (StringUtils.hasText(error) || !StringUtils.hasText(code)) {
            return redirect(FAILURE); // upstream declined / malformed callback
        }
        try {
            federatedAuth.complete(alias, code, state, request, response);
            return redirect(SUCCESS);
        } catch (RuntimeException e) {
            // Browser-navigation endpoint: a failed federated login renders as an SPA redirect, not JSON — and
            // that includes an unexpected failure (a store error, an oversized upstream claim), which would
            // otherwise escape as a stack-trace page. The reason is logged (not shown), so this stays
            // non-revealing either way.
            log.info("Federated login callback rejected for alias={}: {}", alias, e.getClass().getSimpleName());
            log.debug("Federated login callback failure detail", e); // detail stays at DEBUG, off the default log
            return redirect(FAILURE);
        }
    }

    private ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
