package com.example.sso.email.internal.api;

import com.example.sso.email.internal.application.EmailTemplatePreview;
import com.example.sso.email.internal.application.EmailTemplateService;
import com.example.sso.email.internal.application.EmailTemplateView;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for the acting tenant's per-event email templates. Reads list each event with the tier's own
 * template (or the inherited default as a starting point); writes are step-up-gated and org-scoped in the
 * service (fail-closed on a bound-orgless non-platform caller). Preview renders unsaved content with sample
 * data — no persistence.
 */
@RestController
@RequestMapping("/api/admin/email-templates")
@RequiredArgsConstructor
public class EmailTemplateController {

    private final EmailTemplateService service;

    @GetMapping
    @RequirePermission(Permissions.EMAIL_TEMPLATE_READ)
    public List<EmailTemplateView> list() {
        return service.list();
    }

    @PutMapping("/{event}")
    @RequirePermission(Permissions.EMAIL_TEMPLATE_UPDATE)
    @RequireStepUp
    public List<EmailTemplateView> update(@PathVariable EmailEvent event,
            @Valid @RequestBody EmailTemplateRequest request) {
        return service.update(event, request.toSpec());
    }

    @DeleteMapping("/{event}")
    @RequirePermission(Permissions.EMAIL_TEMPLATE_UPDATE)
    @RequireStepUp
    public List<EmailTemplateView> delete(@PathVariable EmailEvent event) {
        return service.delete(event);
    }

    @PostMapping("/{event}/preview")
    @RequirePermission(Permissions.EMAIL_TEMPLATE_READ)
    public EmailTemplatePreview preview(@PathVariable EmailEvent event,
            @Valid @RequestBody EmailTemplateRequest request) {
        return service.preview(event, request.toSpec());
    }
}
