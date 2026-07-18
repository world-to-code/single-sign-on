package com.example.sso.email.internal.application;

import com.example.sso.email.internal.domain.EmailTemplate;
import com.example.sso.email.internal.domain.EmailTemplateRepository;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailTemplateService}: upsert to the acting tenant, the fail-closed platform-write
 * guard (symmetric on read and write), delete reverting to the default, the https-only logo rule, and preview
 * rendering unsaved content with sample data without persisting.
 */
@ExtendWith(MockitoExtension.class)
class EmailTemplateServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final EmailEvent EVENT = EmailEvent.EMAIL_VERIFICATION_CODE;

    @Mock
    EmailTemplateRepository repository;
    @Mock
    OrgContext orgContext;

    private EmailTemplateService service() {
        return new EmailTemplateService(repository, new DefaultEmailTemplates(), new EmailTemplateRenderer(), orgContext);
    }

    private EmailTemplateSpec spec(String logoUrl) {
        return new EmailTemplateSpec("Subject {{code}}", "<b>{{code}}</b>", "code {{code}}", logoUrl);
    }

    @Test
    void listAdvertisesEachEventWithItsAllowedVariablesAndTheDefaultAsAStartingPoint() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(eq(ORG), any())).thenReturn(Optional.empty());

        List<EmailTemplateView> views = service().list();

        assertThat(views).hasSize(EmailEvent.values().length);
        EmailTemplateView code = views.stream().filter(v -> v.event() == EVENT).findFirst().orElseThrow();
        assertThat(code.configured()).isFalse();                          // inherits the default
        assertThat(code.variables()).contains("code", "ttlMinutes", EmailEvent.LOGO_URL);
        assertThat(code.subject()).isNotBlank();                          // the default content, as a starting point
    }

    @Test
    void updateSavesTheActingTenantsRow() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.empty());

        service().update(EVENT, spec("https://cdn.example/l.png"));

        ArgumentCaptor<EmailTemplate> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(saved.getValue().getEvent()).isEqualTo(EVENT);
        assertThat(saved.getValue().getLogoUrl()).isEqualTo("https://cdn.example/l.png");
    }

    @Test
    void updateReconfiguresAnExistingRowInPlace() {
        EmailTemplate existing = EmailTemplate.create(ORG, EVENT, "old", "<p>old</p>", "old", null);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.of(existing));

        service().update(EVENT, spec(null));

        verify(repository, never()).save(any());
        assertThat(existing.getSubject()).isEqualTo("Subject {{code}}");
    }

    @Test
    void updateRejectsANonHttpsLogoUrlAndDoesNotPersist() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service().update(EVENT, spec("http://cdn.example/l.png")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsAMalformedTemplateSyntaxAndDoesNotPersist() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        // An unclosed section would throw at RENDER time (breaking an OTP send) — reject it at save instead.
        assertThatThrownBy(() -> service().update(EVENT,
                new EmailTemplateSpec("Subject", "{{#code}}never closed", null, null)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void everyWhitelistedVariableHasASampleValueForPreview() {
        // Guards the hand-kept sampleVars against an event's variable whitelist growing without a sample value
        // (which would render an empty, confusing preview).
        for (EmailEvent event : EmailEvent.values()) {
            for (String variable : event.variables()) {
                EmailTemplatePreview preview = service().preview(event,
                        new EmailTemplateSpec("s", "[{{" + variable + "}}]", null, null));
                assertThat(preview.html()).as("event %s variable %s must have a sample value", event, variable)
                        .isNotEqualTo("[]");
            }
        }
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotWriteTheGlobalDefault() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service().update(EVENT, spec(null))).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void listDoesNotSurfaceTheGlobalRowToABoundOrglessNonPlatformCaller() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThat(service().list()).allMatch(v -> !v.configured());
        verify(repository, never()).findByOrgIdIsNullAndEvent(any()); // never resolves the global row as "own"
    }

    @Test
    void deleteRemovesTheActingTenantsRow() {
        EmailTemplate existing = EmailTemplate.create(ORG, EVENT, "s", "<p>h</p>", "t", null);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndEvent(ORG, EVENT)).thenReturn(Optional.of(existing));

        service().delete(EVENT);

        verify(repository).delete(existing);
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotDelete() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service().delete(EVENT)).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void previewRendersProvidedContentWithSampleDataAndDoesNotPersist() {
        EmailTemplatePreview preview = service().preview(EVENT, spec("https://cdn.example/l.png"));

        assertThat(preview.html()).isEqualTo("<b>123456</b>"); // sample code interpolated + escaped-safe
        assertThat(preview.subject()).isEqualTo("Subject 123456");
        verify(repository, never()).save(any());
    }
}
