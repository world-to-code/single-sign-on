package com.example.sso.email.internal.application;

import com.example.sso.email.internal.domain.EmailTemplate;
import com.example.sso.email.internal.domain.EmailTemplateRepository;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.tenancy.OrgContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailComposerImpl} with the REAL renderer + built-in defaults (only the repository and
 * org context are mocked): own → platform → built-in resolution, variable interpolation, HTML-escaping of a
 * hostile value, an unknown variable rendering empty (never an error / never evaluated), and the text
 * alternative falling back to the default when a template omits it.
 */
@ExtendWith(MockitoExtension.class)
class EmailComposerImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final EmailEvent EVENT = EmailEvent.EMAIL_VERIFICATION_CODE;

    @Mock
    EmailTemplateRepository repository;
    @Mock
    OrgContext orgContext;

    private EmailComposerImpl composer() {
        return new EmailComposerImpl(repository, new DefaultEmailTemplates(), new EmailTemplateRenderer(), orgContext);
    }

    private EmailTemplate template(UUID orgId, String subject, String html, String text, String logo) {
        return EmailTemplate.create(orgId, EVENT, subject, html, text, logo);
    }

    @Test
    void rendersTheTenantsOwnTemplateInterpolatedWithHtmlEscapedValues() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.of(template(ORG,
                "Hi {{slug}}", "<img src=\"{{logoUrl}}\"><b>{{code}}</b>", "code {{code}}", "https://cdn.example/l.png")));

        OutboundEmail email = composer().compose(EVENT, "to@x.example", Map.of("code", "<script>", "slug", "acme"));

        assertThat(email.subject()).isEqualTo("Hi acme");                       // subject not HTML — not escaped
        assertThat(email.htmlBody()).contains("&lt;script&gt;").doesNotContain("<script>"); // escaped in HTML
        assertThat(email.htmlBody()).contains("https://cdn.example/l.png");     // logoUrl injected
        assertThat(email.textBody()).isEqualTo("code <script>");                // text not HTML — not escaped
    }

    @Test
    void fallsBackToTheGlobalTemplateWhenTheTenantHasNone() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNullAndEvent(EVENT)).thenReturn(Optional.of(template(null,
                "Global {{code}}", "<p>{{code}}</p>", "global {{code}}", null)));

        OutboundEmail email = composer().compose(EVENT, "to@x.example", Map.of("code", "123456", "ttlMinutes", 10));

        assertThat(email.subject()).isEqualTo("Global 123456");
    }

    @Test
    void fallsBackToTheBuiltInDefaultWhenNoTemplateExists() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNullAndEvent(EVENT)).thenReturn(Optional.empty());

        OutboundEmail email = composer().compose(EVENT, "to@x.example", Map.of("code", "123456", "ttlMinutes", 10));

        assertThat(email.textBody()).isEqualTo("Your verification code is: 123456\n\nIt expires in 10 minutes.");
        assertThat(email.htmlBody()).contains("123456");
    }

    @Test
    void anUnknownVariableRendersEmptyNotAnError() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.of(template(ORG,
                "s", "X{{bogus}}Y", "t", null)));

        OutboundEmail email = composer().compose(EVENT, "to@x.example", Map.of("code", "1"));

        assertThat(email.htmlBody()).isEqualTo("XY");
    }

    @Test
    void aTemplateWithoutATextBodyUsesTheDefaultText() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.of(template(ORG,
                "s", "<p>{{code}}</p>", null, null)));

        OutboundEmail email = composer().compose(EVENT, "to@x.example", Map.of("code", "123456", "ttlMinutes", 10));

        assertThat(email.textBody()).isEqualTo("Your verification code is: 123456\n\nIt expires in 10 minutes.");
    }

    @Test
    void aNullOrgResolvesOnlyThePlatformTemplateThenTheBuiltInDefault() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNullAndEvent(EVENT)).thenReturn(Optional.empty());

        OutboundEmail email = composer().compose(EVENT, "to@x.example", Map.of("code", "1", "ttlMinutes", 10));

        verify(repository, never()).findByOrgIdAndEvent(any(), any()); // an org-scoped read is never issued
        assertThat(email.subject()).isEqualTo("Verify your email for Mini SSO"); // the built-in default
    }

    @Test
    void aSubjectRenderedWithCrlfIsFlattenedToASingleLineSoItCannotInjectAHeader() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.of(template(ORG,
                "Verify\r\nBcc: attacker@evil.example", "<p>{{code}}</p>", "t", null)));

        OutboundEmail email = composer().compose(EVENT, "to@x.example", Map.of("code", "1"));

        assertThat(email.subject()).doesNotContain("\r").doesNotContain("\n")
                .isEqualTo("Verify Bcc: attacker@evil.example");
    }

    @Test
    void aHostileLogoUrlCannotBreakOutOfTheImgSrcAttribute() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.of(template(ORG,
                "s", "<img src=\"{{logoUrl}}\">", "t", "https://cdn.example/l.png\" onerror=\"alert(1)")));

        OutboundEmail email = composer().compose(EVENT, "to@x.example", Map.of("code", "1"));

        assertThat(email.htmlBody()).doesNotContain("onerror=\"alert(1)"); // the quote is HTML-escaped, no breakout
        assertThat(email.htmlBody()).contains("&quot;");
    }
}
