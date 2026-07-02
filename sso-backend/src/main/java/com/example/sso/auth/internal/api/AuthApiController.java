package com.example.sso.auth.internal.api;

import com.example.sso.auth.internal.application.AuthSessionView;
import com.example.sso.auth.internal.application.AuthStateService;
import com.example.sso.auth.internal.application.LoginAttemptService;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.auth.internal.application.FactorChallenge;
import com.example.sso.auth.internal.application.FactorHandlers;
import com.example.sso.auth.internal.application.FactorVerificationRequest;
import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.mfa.MfaService;
import com.example.sso.portal.AppType;
import com.example.sso.portal.AppStepUpFilter;
import com.example.sso.session.SessionMetadata;
import com.example.sso.session.SessionMetadataStore;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.StepUpInterceptor;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.user.UserAccount;
import com.example.sso.user.RoleRef;
import com.example.sso.user.UserService;
import com.example.sso.webauthn.PasskeyService;
import com.example.sso.webauthn.PasskeyView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON authentication API driving the React login/MFA flow over a session cookie. The required
 * factors and their order come from the user's authentication policy; the SPA polls
 * {@code GET /session} and, for the current step, calls the generic factor endpoints
 * ({@code /factors/{factor}/prepare} and {@code /factors/{factor}/verify}) which dispatch to a
 * {@link FactorHandlers factor strategy}. When the policy is satisfied, {@code MFA_COMPLETE} is granted.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private final AuthenticationManager authenticationManager;
    private final FactorAuthorizationService factorAuth;
    private final AuthStateService authState;
    private final UserService users;
    private final UserDetailsService userDetailsService;
    private final PasskeyService passkeys;
    private final LoginAttemptService loginAttempts;
    private final FactorHandlers factorHandlers;
    private final SessionPolicyService sessionPolicy;
    private final AuthPolicyResolver authPolicies;
    private final SessionRegistry sessionRegistry;
    private final SessionMetadataStore sessionMetadata;
    private final MfaService mfaService;
    private final AuditService audit;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public AuthApiController(AuthenticationManager authenticationManager, FactorAuthorizationService factorAuth,
                            AuthStateService authState, UserService users, UserDetailsService userDetailsService,
                            PasskeyService passkeys, LoginAttemptService loginAttempts,
                            FactorHandlers factorHandlers, SessionPolicyService sessionPolicy,
                            AuthPolicyResolver authPolicies, SessionRegistry sessionRegistry,
                            SessionMetadataStore sessionMetadata, MfaService mfaService, AuditService audit) {
        this.authenticationManager = authenticationManager;
        this.factorAuth = factorAuth;
        this.authState = authState;
        this.users = users;
        this.userDetailsService = userDetailsService;
        this.passkeys = passkeys;
        this.loginAttempts = loginAttempts;
        this.factorHandlers = factorHandlers;
        this.sessionPolicy = sessionPolicy;
        this.authPolicies = authPolicies;
        this.sessionRegistry = sessionRegistry;
        this.sessionMetadata = sessionMetadata;
        this.mfaService = mfaService;
        this.audit = audit;
    }

    @GetMapping("/session")
    public AuthSessionView session() {
        return authState.describe(currentAuthentication());
    }

    /**
     * Identifier-first login: the user submits their email; we resolve the matching account and drive
     * <em>its</em> authentication policy (the first factor is whatever the policy dictates). Accounts are
     * admin-provisioned (invite-only), so an email with no active account is REJECTED here — only a real,
     * enabled account can start a sign-in. This intentionally reveals account existence, which is
     * acceptable for an admin-managed directory.
     *
     * <p>Multi-tenant note: this lookup is the tenant-resolution point. Before going multi-tenant, resolve
     * the tenant first (e.g. by email domain or an explicit selector) and scope {@code findByLogin} to it.
     */
    @PostMapping("/identify")
    public ResponseEntity<AuthSessionView> identify(@Valid @RequestBody IdentifyRequest request,
                                                    HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UserAccount user = users.findByLogin(request.email()).filter(UserAccount::isEnabled).orElse(null);
        if (user == null) {
            audit.record(new AuditRecord(AuditType.AUTH_IDENTIFY, request.email(), false, "no active account", null));
            throw new NotFoundException("No active account for that email. Contact your administrator.");
        }

        Authentication preAuth = UsernamePasswordAuthenticationToken.authenticated(
                user.getUsername(), null, List.of()); // identified, no factors yet
        factorAuth.establish(httpRequest, httpResponse, preAuth);
        audit.record(AuditType.AUTH_IDENTIFY, user.getUsername(), true);
        return ResponseEntity.ok(completeIfSatisfied(httpRequest, httpResponse));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthSessionView> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            factorAuth.establish(httpRequest, httpResponse, authentication); // password factor granted by provider

            loginAttempts.onSuccess(request.username());
            audit.record(AuditType.AUTH_SUCCESS, request.username(), true);
            return ResponseEntity.ok(completeIfSatisfied(httpRequest, httpResponse));
        } catch (AuthenticationException e) {
            loginAttempts.onFailure(request.username());
            audit.record(new AuditRecord(AuditType.AUTH_FAILURE, request.username(), false, e.getMessage(), null));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    // Self-service signup is intentionally NOT offered: accounts are provisioned by an administrator
    // (invite-only). Users are created via the admin API / SCIM and complete enrollment at first login.

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = currentAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID", "XSRF-TOKEN").logout(request, response, authentication);
    }

    // --- Generic factor steps (dispatch to the per-factor strategy) ---

    /** Issues any pre-step data for the factor (TOTP QR, WebAuthn options, or sends an email code). */
    @PostMapping("/factors/{factor}/prepare")
    public FactorChallenge prepareFactor(@PathVariable AuthFactor factor, HttpServletRequest request) {
        UserAccount user = requireUser();
        requireCurrentStep(factor); // can only act on the factor the policy currently expects

        // Keycloak-style gate: setting up an un-enrolled factor (TOTP authenticator or a passkey)
        // during login is only allowed when the session policy permits enroll-at-login.
        boolean enrollable = factor == AuthFactor.TOTP || factor == AuthFactor.FIDO2;
        if (enrollable && !factorHandlers.isEnrolled(factor, user)
                && !authPolicies.resolveForUser(user).isAllowEnrollmentAtLogin()) {
            throw new ForbiddenException(
                    "Setting up a new authenticator during login is disabled. Contact your administrator.");
        }

        return factorHandlers.get(factor).prepare(user, request);
    }

    /** Verifies the user's response for the factor; on success grants it and advances the policy. */
    @PostMapping("/factors/{factor}/verify")
    public ResponseEntity<AuthSessionView> verifyFactor(@PathVariable AuthFactor factor,
                                                        @RequestBody FactorVerificationRequest verification,
                                                        HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UserAccount user = requireUser();
        requireCurrentStep(factor); // reject factors out of policy order (e.g. TOTP before password)

        // Account lockout applies to every factor (password is now verified here too, not just /login).
        if (user.isTemporarilyLocked(Instant.now()) || !user.isAccountNonLocked()) {
            audit.record(new AuditRecord(AuditType.MFA_LOCKED, user.getUsername(), false, "factor=" + factor.name(), null));
            return ResponseEntity.status(HttpStatus.LOCKED).build();
        }

        if (factorHandlers.get(factor).verify(user, verification, httpRequest)) {
            loginAttempts.onSuccess(user.getUsername());
            factorAuth.grantFactor(httpRequest, httpResponse, factor.authority());
            stampAppStepUp(httpRequest); // if this verify is part of an app step-up, refresh its freshness clock
            audit.record(new AuditRecord(AuditType.MFA_SUCCESS, user.getUsername(), true, "factor=" + factor.name(), null));
            return ResponseEntity.ok(completeIfSatisfied(httpRequest, httpResponse));
        }

        loginAttempts.onFailure(user.getUsername());
        audit.record(new AuditRecord(AuditType.MFA_FAILURE, user.getUsername(), false, "factor=" + factor.name(), null));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    // --- Passkey self-service management ---

    @GetMapping("/passkeys")
    public List<PasskeyView> listPasskeys() {
        return passkeys.list(requireMfaComplete());
    }

    @DeleteMapping("/passkeys/{credentialId}")
    public ResponseEntity<Void> deletePasskey(@PathVariable String credentialId) {
        passkeys.delete(requireMfaComplete(), credentialId);
        return ResponseEntity.noContent().build();
    }

    // --- Self-service authenticator (TOTP) enrollment for an already-signed-in user ---

    /** Starts TOTP setup from "My Profile": returns the secret + scannable QR (stored pending in session). */
    @PostMapping("/factors/totp/setup")
    public FactorChallenge setupTotp(HttpServletRequest request) {
        UserAccount user = requireMfaComplete();
        if (factorHandlers.isEnrolled(AuthFactor.TOTP, user)) {
            throw new ConflictException("An authenticator is already set up. Remove it first to re-enroll.");
        }

        return factorHandlers.get(AuthFactor.TOTP).prepare(user, request);
    }

    /** Confirms TOTP setup by verifying a code against the freshly scanned secret; persists on success. */
    @PostMapping("/factors/totp/setup/confirm")
    public ResponseEntity<Void> confirmTotpSetup(@RequestBody FactorVerificationRequest verification,
                                                 HttpServletRequest request) {
        UserAccount user = requireMfaComplete();
        if (factorHandlers.get(AuthFactor.TOTP).verify(user, verification, request)) {
            audit.record(AuditType.TOTP_ENROLLED, user.getUsername(), true);
            return ResponseEntity.ok().build();
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    /** Removes the signed-in user's TOTP authenticator (so it can be re-enrolled). */
    @DeleteMapping("/factors/totp")
    public ResponseEntity<Void> disableTotp() {
        UserAccount user = requireMfaComplete();
        mfaService.resetMfa(user.getId());
        audit.record(AuditType.TOTP_REMOVED, user.getUsername(), true);
        return ResponseEntity.noContent().build();
    }

    // --- Self-service "My Profile": account summary, active sessions, revoke ---

    /** Roll-up of the signed-in user's own identity + security factors. */
    @GetMapping("/profile")
    public ProfileView profile() {
        UserAccount user = requireMfaComplete();
        int passkeyCount = passkeys.list(user).size();
        List<String> roles = user.getRoles().stream().map(RoleRef::getName).sorted().toList();

        return new ProfileView(user.getUsername(), user.getEmail(), user.getDisplayName(), user.isEmailVerified(),
                mfaService.hasEnabledTotp(user.getId()), passkeyCount > 0, passkeyCount, roles);
    }

    /** The current user's active sessions (metadata store joined with SessionRegistry liveness). */
    @GetMapping("/sessions")
    public List<SessionDeviceView> sessions(HttpServletRequest request) {
        UserAccount user = requireMfaComplete();
        HttpSession current = request.getSession(false);
        String currentId = current == null ? null : current.getId();

        // Guarantee the caller's CURRENT session is always tracked + shown: backfill the registry +
        // metadata if missing (e.g. an in-memory restart cleared the store, or it was never recorded).
        if (currentId != null) {
            if (sessionRegistry.getSessionInformation(currentId) == null) {
                sessionRegistry.registerNewSession(currentId, user.getUsername());
            }
            boolean tracked = sessionMetadata.forUser(user.getUsername()).stream()
                    .anyMatch(m -> m.sessionId().equals(currentId));
            if (!tracked) {
                sessionMetadata.record(currentId, user.getUsername(), request.getHeader("User-Agent"), clientIp(request));
            }
        }

        return sessionMetadata.forUser(user.getUsername()).stream()
                .filter(this::isLive) // hide sessions the registry has expired/forgotten
                .map(m -> new SessionDeviceView(m.handle(), m.sessionId().equals(currentId),
                        parseDevice(m.userAgent()), m.userAgent(), m.ip(), m.createdAt(), m.lastSeenAt()))
                .toList();
    }

    /**
     * Revokes one of the caller's OWN sessions, identified by its opaque public handle. The lookup is
     * scoped to the current user's sessions, so another user's session is never reachable (404) and the
     * real session id is never exposed. Revoking the current session is allowed — it logs the user out
     * on the next request.
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revokeSession(@PathVariable String id) {
        UserAccount user = requireMfaComplete();
        SessionMetadata target = sessionMetadata.findByUserAndHandle(user.getUsername(), id)
                .orElseThrow(() -> new NotFoundException("session not found"));

        SessionInformation info = sessionRegistry.getSessionInformation(target.sessionId());
        if (info != null) {
            info.expireNow(); // SessionIntegrityFilter rejects + invalidates it on the next request
        }

        sessionMetadata.remove(target.sessionId());
        audit.record(new AuditRecord(AuditType.SESSION_REVOKED, user.getUsername(), true, "handle=" + target.handle(), null));
        return ResponseEntity.noContent().build();
    }

    private boolean isLive(SessionMetadata metadata) {
        SessionInformation info = sessionRegistry.getSessionInformation(metadata.sessionId());
        return info != null && !info.isExpired();
    }

    /** Best-effort first-hop client IP (honours a single X-Forwarded-For entry behind a proxy). */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Tiny best-effort "Browser on OS" label from a User-Agent string (display only). */
    private String parseDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown device";
        }

        String ua = userAgent;
        String browser = ua.contains("Edg/") ? "Edge"
                : ua.contains("OPR/") || ua.contains("Opera") ? "Opera"
                : ua.contains("Firefox") ? "Firefox"
                : (ua.contains("Chrome") && !ua.contains("Chromium")) ? "Chrome"
                : (ua.contains("Safari") && !ua.contains("Chrome")) ? "Safari"
                : "Browser";
        String os = ua.contains("Windows") ? "Windows"
                : (ua.contains("Mac OS X") || ua.contains("Macintosh")) ? "macOS"
                : ua.contains("Android") ? "Android"
                : (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iOS")) ? "iOS"
                : ua.contains("Linux") ? "Linux"
                : "Unknown OS";

        return browser + " on " + os;
    }

    /**
     * Finalizes the session after Spring's passwordless {@code /login/webauthn}: records the FIDO2
     * factor (if the WebAuthn login did not tag our authority) and grants MFA_COMPLETE when the
     * policy is satisfied. Safe to call after any login.
     */
    @PostMapping("/complete")
    public AuthSessionView complete(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = currentAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            boolean hasFido2 = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).anyMatch(Factors.FIDO2::equals);
            boolean webauthnLogin = authentication.getClass().getSimpleName().equals("WebAuthnAuthentication");
            if (webauthnLogin && !hasFido2) {
                factorAuth.grantFactor(request, response, Factors.FIDO2);
            }
        }

        return completeIfSatisfied(request, response);
    }

    @GetMapping("/resume")
    public Map<String, String> resume(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = requestCache.getRequest(request, response);
        return Map.of("redirectUrl", saved != null ? saved.getRedirectUrl() : "/");
    }

    // --- Step-up re-authentication (for sensitive operations) ---

    /** Pre-step data for a re-auth factor (e.g. WebAuthn options); must be a policy-allowed re-auth factor. */
    @PostMapping("/reauth/{factor}/prepare")
    public FactorChallenge prepareReauth(@PathVariable AuthFactor factor, HttpServletRequest request) {
        UserAccount user = requireUser();
        requireReauthFactor(sessionPolicy.resolveForUser(user), factor);
        return factorHandlers.get(factor).prepare(user, request);
    }

    /** Verifies a fresh factor and refreshes the step-up clock so sensitive operations may proceed. */
    @PostMapping("/reauth/{factor}/verify")
    public ResponseEntity<Void> reauth(@PathVariable AuthFactor factor,
                                       @RequestBody FactorVerificationRequest verification,
                                       HttpServletRequest request, HttpServletResponse response) {
        UserAccount user = requireUser();
        SessionPolicyDetails policy = sessionPolicy.resolveForUser(user);
        requireReauthFactor(policy, factor);

        if (factorHandlers.get(factor).verify(user, verification, request)) {
            // Per-policy defence in depth: rotate the session id on a successful re-auth BEFORE the
            // response (and the step-up stamp) are written, keeping the SessionRegistry consistent.
            if (policy.isRotateOnReauth()) {
                rotateSessionId(request, user.getUsername());
            }

            StepUpInterceptor.stamp(request.getSession(true));
            // Re-stamp the session Authentication's auth-time marker so an admin elevation token minted
            // from the OIDC flow right after this step-up carries a FRESH auth_time (RFC 9470 step-up).
            factorAuth.restampAuthTime(request, response);
            audit.record(new AuditRecord(AuditType.REAUTH_SUCCESS, user.getUsername(), true, "factor=" + factor, null));
            return ResponseEntity.ok().build();
        }

        audit.record(new AuditRecord(AuditType.REAUTH_FAILURE, user.getUsername(), false, "factor=" + factor, null));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    private void requireReauthFactor(SessionPolicyDetails policy, AuthFactor factor) {
        boolean allowed = Arrays.stream(policy.getReauthFactors().split(","))
                .map(String::trim).anyMatch(f -> f.equals(factor.name()));
        if (!allowed) {
            throw new BadRequestException(factor + " is not an allowed re-auth factor");
        }
    }

    /** Rotates the JSESSIONID and keeps the concurrent-session registry AND device-metadata keyed by the new id. */
    private void rotateSessionId(HttpServletRequest request, String username) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        String oldId = session.getId();
        boolean tracked = sessionRegistry.getSessionInformation(oldId) != null;
        String newId = request.changeSessionId();
        if (!oldId.equals(newId)) {
            if (tracked) {
                sessionRegistry.removeSessionInformation(oldId);
                sessionRegistry.registerNewSession(newId, username);
            }
            // changeSessionId() fires sessionIdChanged (not sessionDestroyed), so the cleanup listener
            // never drops the old-id entry — re-key the device metadata to the new id ourselves, else the
            // current session disappears from "My Profile" and the old entry leaks.
            sessionMetadata.rekey(oldId, newId);
        }
    }

    /**
     * Once the policy is satisfied, upgrades the (possibly identifier-first, authority-less)
     * session to a fully-authenticated one: loads the user's real authorities, keeps the granted
     * factors, and adds {@code MFA_COMPLETE}. This is also where the user's roles first enter the
     * session — they are never present during the identified-but-unauthenticated phase.
     */
    private AuthSessionView completeIfSatisfied(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = currentAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return authState.describe(authentication);
        }

        boolean alreadyComplete = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet()).contains(Factors.MFA_COMPLETE);
        if (!alreadyComplete && authState.isPolicySatisfied(authentication)) {
            UserDetails principal = userDetailsService.loadUserByUsername(authentication.getName());
            Set<GrantedAuthority> authorities = new LinkedHashSet<>(principal.getAuthorities());
            authentication.getAuthorities().stream()
                    .filter(a -> a.getAuthority().startsWith("FACTOR_")).forEach(authorities::add);
            authorities.add(new SimpleGrantedAuthority(Factors.MFA_COMPLETE));
            // Carry the authentication time as a marker authority so the OIDC token customizer can emit
            // the standard `auth_time` claim. (A details object would break JdbcOAuth2AuthorizationService,
            // whose Jackson validator rejects arbitrary types; GrantedAuthority serializes fine.)
            authorities.add(new SimpleGrantedAuthority(Factors.AUTH_TIME_PREFIX + Instant.now().getEpochSecond()));
            factorAuth.establish(request, response,
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
            StepUpInterceptor.stamp(request.getSession(false)); // fresh auth time for step-up
            enforceMaxConcurrentSessions(request, principal.getUsername());
            audit.record(new AuditRecord(AuditType.SESSION_CREATED, principal.getUsername(), true, null, clientIp(request)));
        }

        return authState.describe(currentAuthentication());
    }

    /**
     * Registers the just-completed session with the {@link SessionRegistry} and, if the user's
     * resolved session policy caps concurrent sessions, expires the OLDEST overflow sessions (by
     * last-request time). The owning sessions are then rejected + invalidated by the
     * {@code SessionIntegrityFilter} on their next request. Done manually because our custom JSON
     * login flow does not run Spring's {@code ConcurrentSessionControlAuthenticationStrategy}.
     */
    private void enforceMaxConcurrentSessions(HttpServletRequest request, String username) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        if (sessionRegistry.getSessionInformation(session.getId()) == null) {
            sessionRegistry.registerNewSession(session.getId(), username);
        }

        // Stamp device metadata for the self-service "My Profile" sessions list (single-node, in-memory).
        sessionMetadata.record(session.getId(), username, request.getHeader("User-Agent"), clientIp(request));
        SessionPolicyDetails policy = sessionPolicy.resolveForUsername(username);
        int max = policy.getMaxConcurrentSessions();
        if (max <= 0) {
            return; // 0 = unlimited
        }

        List<SessionInformation> active = new ArrayList<>(sessionRegistry.getAllSessions(username, false));
        if (active.size() <= max) {
            return;
        }

        active.sort(Comparator.comparing(SessionInformation::getLastRequest)); // oldest first
        active.stream().limit(active.size() - (long) max).forEach(SessionInformation::expireNow);
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private UserAccount requireUser() {
        Authentication authentication = currentAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException();
        }

        return users.findByUsername(authentication.getName())
                .orElseThrow(UnauthorizedException::new);
    }

    /** Self-service management requires a fully-authenticated session, not just an identified one. */
    private UserAccount requireMfaComplete() {
        UserAccount user = requireUser();
        boolean complete = currentAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Factors.MFA_COMPLETE::equals);
        if (!complete) {
            throw new ForbiddenException("Finish signing in first.");
        }

        return user;
    }

    /**
     * During the initial (pre-MFA_COMPLETE) login, rejects acting on a factor that is not the policy's
     * current step — this prevents skipping steps or planting a factor before authentication. Once the
     * session is fully authenticated, the /factors endpoints are reused for per-app step-up (the user
     * adds an extra factor an app requires), where login step-ordering no longer applies — so allow it.
     */
    private void requireCurrentStep(AuthFactor factor) {
        AuthSessionView view = authState.describe(currentAuthentication());
        if (AuthSessionView.NEXT_DONE.equals(view.next())) {
            return; // fully authenticated -> step-up context, not initial login ordering
        }

        if (!view.pendingFactors().contains(factor.name())) {
            throw new BadRequestException("Not the expected authentication step.");
        }
    }

    /**
     * When this factor verification is part of an app step-up (an app launch is pending in the session),
     * stamp the deliberate-step-up clock so the per-app policy freshness window is (re)started. Plain
     * login verifications have no pending app and therefore never satisfy an app policy.
     */
    private void stampAppStepUp(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        Object type = session.getAttribute(AppStepUpFilter.APP_TYPE);
        Object appId = session.getAttribute(AppStepUpFilter.APP_ID);
        if (type instanceof String t && appId instanceof String id) {
            session.setAttribute(AppStepUpFilter.stepUpTimeKey(AppType.valueOf(t), id),
                    System.currentTimeMillis());
        }
    }
}
