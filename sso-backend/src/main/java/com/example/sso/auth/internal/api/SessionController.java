package com.example.sso.auth.internal.api;

import com.example.sso.auth.internal.application.SessionDeviceView;
import com.example.sso.auth.internal.application.SessionSelfService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own active sessions: list and self-revoke by handle. */
@RestController
@RequestMapping("/api/auth/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionSelfService sessions;

    @GetMapping
    public List<SessionDeviceView> list(HttpServletRequest request) {
        return sessions.list(request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        sessions.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
