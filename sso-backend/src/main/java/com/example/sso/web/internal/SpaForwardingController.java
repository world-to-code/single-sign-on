package com.example.sso.web.internal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React SPA shell for client-side routes (anything that is not an API, static
 * asset, or protocol endpoint), so deep links / refreshes load {@code index.html} and the
 * SPA router takes over.
 */
@Controller
public class SpaForwardingController {

    @GetMapping(value = {
            "/", "/login", "/stepup", "/apps", "/passkeys", "/applications", "/users", "/groups", "/auth-policies", "/clients",
            "/relying-parties", "/scim-tokens", "/session-policy", "/ip-ranges", "/audit", "/profile",
            // Admin console entry points (client-side routes): the OIDC callback and every /admin/* page.
            "/admin", "/admin/**"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
