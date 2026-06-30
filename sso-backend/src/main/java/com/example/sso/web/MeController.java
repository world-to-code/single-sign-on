package com.example.sso.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Returns the currently authenticated principal. Used by the React console and as a
 * simple authenticated endpoint for integration tests.
 */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public CurrentUser me(Authentication authentication) {
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return new CurrentUser(authentication.getName(), authorities);
    }
}
