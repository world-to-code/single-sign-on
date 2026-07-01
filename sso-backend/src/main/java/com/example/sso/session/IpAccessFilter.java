package com.example.sso.session;

import com.example.sso.audit.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Network access control: rejects requests from IP ranges denied by the {@link IpRuleService} rule
 * set (block-list, or allow-list when any ALLOW rule exists) before authentication runs. Health
 * checks are exempt so liveness probes survive a misconfiguration.
 */
@RequiredArgsConstructor
public class IpAccessFilter extends OncePerRequestFilter {
    private final IpRuleService ipRules;
    private final AuditService audit;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        if (!ipRules.isAllowed(ip)) {
            audit.record("IP_BLOCKED", "anonymous", false, "ip=" + ip + " uri=" + request.getRequestURI(), null);
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("Access from your network is not permitted.");
            return;
        }
        chain.doFilter(request, response);
    }
}
