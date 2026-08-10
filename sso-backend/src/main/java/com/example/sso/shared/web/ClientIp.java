package com.example.sso.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client address as something OBSERVED, not as something claimed.
 *
 * <p>This used to read the FIRST entry of {@code X-Forwarded-For}, which is the one entry no proxy wrote: the
 * edge appends what it saw to whatever arrived, so the head of that list is supplied by the caller. Every
 * audit row, session record and log line therefore carried an address of the caller's choosing — and once the
 * trail is exported, that value becomes the field a SIEM rule blocks on, so the caller was choosing the
 * target. It is a claim; it was being recorded as a fact.
 *
 * <p>So the order is trust-first: {@code X-Real-IP}, which the edge sets from the socket peer and overwrites
 * unconditionally; then the LAST {@code X-Forwarded-For} entry, which is the nearest hop's own observation
 * rather than the far end's assertion; then the socket address.
 *
 * <p>None of this helps an instance exposed directly to the internet — no header is evidence without a proxy
 * that guarantees it. The guarantee is the edge's, and this class depends on it.
 */
public final class ClientIp {

    private static final String X_REAL_IP = "X-Real-IP";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    public static String of(HttpServletRequest request) {
        String observed = request.getHeader(X_REAL_IP);
        if (observed != null && !observed.isBlank()) {
            return observed.trim();
        }
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private ClientIp() {
    }
}
