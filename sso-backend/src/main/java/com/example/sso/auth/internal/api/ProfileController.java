package com.example.sso.auth.internal.api;

import com.example.sso.auth.internal.application.ProfileService;
import com.example.sso.auth.internal.application.ProfileView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own "My Profile" roll-up. */
@RestController
@RequestMapping("/api/auth/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profile;

    @GetMapping
    public ProfileView profile() {
        return profile.profile();
    }
}
