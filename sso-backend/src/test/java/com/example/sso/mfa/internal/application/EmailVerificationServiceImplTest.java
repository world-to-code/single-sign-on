package com.example.sso.mfa.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.email.template.EmailComposer;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.tenancy.OrgContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailVerificationServiceImpl}: the OTP send composes the tenant's template (with the
 * code + TTL variables) and runs off the request thread, re-binding the tenant's OrgContext explicitly (the
 * ThreadLocal does not follow the async hop) so the template resolves and the relay routes per-tenant. A null
 * org (a global user) composes and sends without a context.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private final OutboundEmail composed = new OutboundEmail("alice@example.com", "Verify", "<p>h</p>", "t");

    @Mock
    TenantMailSender mailSender;
    @Mock
    EmailComposer composer;
    @Mock
    OrgContext orgContext;

    private EmailVerificationServiceImpl service() {
        return new EmailVerificationServiceImpl(mailSender, composer, orgContext, 10);
    }

    @Test
    void aTenantOtpIsComposedAndSentWithinThatTenantsOrgContext() {
        lenient().when(composer.compose(eq(EmailEvent.EMAIL_VERIFICATION_CODE), eq("alice@example.com"), any()))
                .thenReturn(composed);
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(orgContext).runInOrg(any(), any());

        service().sendCode(ORG, "alice@example.com", "123456");

        verify(orgContext).runInOrg(eq(ORG), any()); // routed via the tenant's own template + relay
        verify(mailSender).send(composed);
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.captor();
        verify(composer).compose(eq(EmailEvent.EMAIL_VERIFICATION_CODE), eq("alice@example.com"), vars.capture());
        assertThat(vars.getValue()).containsEntry("code", "123456").containsEntry("ttlMinutes", 10L);
    }

    @Test
    void aGlobalUserOtpSendsWithoutBindingAnOrgContext() {
        when(composer.compose(any(), any(), any())).thenReturn(composed);

        service().sendCode(null, "root@example.com", "654321");

        verify(orgContext, never()).runInOrg(any(), any());
        verify(mailSender).send(composed); // composed with no bound org → default/platform template
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.captor();
        verify(composer).compose(eq(EmailEvent.EMAIL_VERIFICATION_CODE), eq("root@example.com"), vars.capture());
        assertThat(vars.getValue()).containsEntry("code", "654321").containsEntry("ttlMinutes", 10L);
    }
}
