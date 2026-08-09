package com.example.sso.response.internal.api;

import com.example.sso.response.ResponseScopes;
import com.example.sso.response.internal.application.ResponseActions;
import com.example.sso.response.internal.application.ResponseHoldView;
import com.example.sso.response.internal.application.ResponseTerminationView;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The response API a detection system calls: end this account's sessions, hold it, release it.
 *
 * <p>One subject per call, and no bulk verb at all. That is the cheapest correct answer to the risk this API
 * carries: one false positive from an XDR must not be able to sign out an estate, and an endpoint that cannot
 * express "all of them" cannot be made to do it by a loop that forgot its bound.
 *
 * <p>Each verb names its own scope. The credential a system holds is then exactly the set of things it is
 * meant to do — a detector that only ever ends sessions cannot place a hold, and neither can lift one.
 */
@RestController
@RequestMapping("/api/response/v1/users")
@RequiredArgsConstructor
public class ResponseController {

    private final ResponseActions actions;

    /** The hold in force, if any. Permitted by the scope that can CREATE one — a write implies its read. */
    @GetMapping("/{id}/hold")
    @PreAuthorize("hasAuthority('SCOPE_" + ResponseScopes.HOLD + "')")
    public ResponseHoldView hold(@PathVariable UUID id) {
        return actions.holdInEffect(id).map(ResponseHoldView::of).orElse(ResponseHoldView.NOT_HELD);
    }

    /** Place or re-place the hold. Idempotent by design, so a retry extends rather than stacks. */
    @PutMapping("/{id}/hold")
    @PreAuthorize("hasAuthority('SCOPE_" + ResponseScopes.HOLD + "')")
    public ResponseHoldView placeHold(@PathVariable UUID id, @Valid @RequestBody ResponseHoldRequest request) {
        return ResponseHoldView.of(actions.hold(id, request.reason(), request.duration()));
    }

    /** Release early. Its own scope: undoing a response is the direction worth granting separately. */
    @DeleteMapping("/{id}/hold")
    @PreAuthorize("hasAuthority('SCOPE_" + ResponseScopes.HOLD_LIFT + "')")
    public ResponseEntity<Void> liftHold(@PathVariable UUID id) {
        actions.liftHold(id);
        return ResponseEntity.noContent().build();
    }

    /** End the account's live sessions, propagating to the applications it is signed in to. */
    @DeleteMapping("/{id}/sessions")
    @PreAuthorize("hasAuthority('SCOPE_" + ResponseScopes.SESSION_TERMINATE + "')")
    public ResponseTerminationView terminateSessions(@PathVariable UUID id) {
        return new ResponseTerminationView(actions.terminateSessions(id));
    }
}
