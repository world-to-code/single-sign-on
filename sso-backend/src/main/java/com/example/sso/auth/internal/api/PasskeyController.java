package com.example.sso.auth.internal.api;

import com.example.sso.auth.internal.application.PasskeySelfService;
import com.example.sso.webauthn.PasskeyView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Self-service passkey management for the signed-in user. */
@RestController
@RequestMapping("/api/auth/passkeys")
@RequiredArgsConstructor
public class PasskeyController {

    private final PasskeySelfService passkeys;

    @GetMapping
    public List<PasskeyView> list() {
        return passkeys.list();
    }

    @DeleteMapping("/{credentialId}")
    public ResponseEntity<Void> delete(@PathVariable String credentialId) {
        passkeys.delete(credentialId);
        return ResponseEntity.noContent().build();
    }
}
