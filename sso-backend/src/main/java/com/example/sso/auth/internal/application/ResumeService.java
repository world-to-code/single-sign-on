package com.example.sso.auth.internal.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Service;

/** Resolves the post-login redirect target saved before the user was bounced to the login page. */
@Service
public class ResumeService {

    private static final String DEFAULT_REDIRECT = "/";

    private final RequestCache requestCache = new HttpSessionRequestCache();

    public ResumeView resume(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = requestCache.getRequest(request, response);
        return new ResumeView(saved != null ? saved.getRedirectUrl() : DEFAULT_REDIRECT);
    }
}
