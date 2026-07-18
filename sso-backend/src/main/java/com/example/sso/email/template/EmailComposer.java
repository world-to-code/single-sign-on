package com.example.sso.email.template;

import java.util.Map;

/**
 * Renders an {@link OutboundEmail} for an {@link EmailEvent} from the acting tenant's template — resolved
 * own-template → platform-template → built-in default, so a send never fails for want of customization. Runs
 * under the ambient {@code OrgContext} (the async send paths bind the org before calling), so template
 * resolution is tenant-scoped. Rendering is logic-less: {@code vars} are interpolated and HTML-escaped, never
 * evaluated, so a tenant-authored template cannot execute server-side code.
 */
public interface EmailComposer {

    OutboundEmail compose(EmailEvent event, String to, Map<String, Object> vars);
}
