package com.example.sso.mfa.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link EmailVerificationServiceImpl}: the OTP send runs off the request thread, so the code
 * re-binds the tenant's OrgContext explicitly (the ThreadLocal does not follow the async hop) so
 * {@link TenantMailSender} routes through that org's relay. A null org (a global user) sends without a context.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    TenantMailSender mailSender;
    @Mock
    OrgContext orgContext;

    private EmailVerificationServiceImpl service() {
        return new EmailVerificationServiceImpl(mailSender, orgContext, 10);
    }

    @Test
    void aTenantOtpIsSentWithinThatTenantsOrgContext() {
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(orgContext).runInOrg(any(), any());

        service().sendCode(ORG, "alice@example.com", "123456");

        verify(orgContext).runInOrg(eq(ORG), any()); // routed via the tenant's own relay
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.captor();
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("alice@example.com");
        assertThat(sent.getValue().getText()).contains("123456").contains("10 minutes");
    }

    @Test
    void aGlobalUserOtpSendsWithoutBindingAnOrgContext() {
        service().sendCode(null, "root@example.com", "654321");

        verify(orgContext, never()).runInOrg(any(), any());
        verify(mailSender).send(any(SimpleMailMessage.class)); // falls through to the platform relay
    }
}
