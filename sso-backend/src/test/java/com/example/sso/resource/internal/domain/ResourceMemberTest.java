package com.example.sso.resource.internal.domain;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for the {@link ResourceMember} factory methods (the compact-constructor UUID validation
 * is covered by {@code ResourceTest}). Pure value-object construction — asserted on the result.
 */
class ResourceMemberTest {

    @Test
    void groupFactoryStoresTheUuidAsText() {
        UUID id = UUID.randomUUID();

        ResourceMember member = ResourceMember.group(id);

        assertThat(member.memberType()).isEqualTo(MemberType.GROUP);
        assertThat(member.memberId()).isEqualTo(id.toString());
    }

    @Test
    void userFactoryStoresTheUuidAsText() {
        UUID id = UUID.randomUUID();

        ResourceMember member = ResourceMember.user(id);

        assertThat(member.memberType()).isEqualTo(MemberType.USER);
        assertThat(member.memberId()).isEqualTo(id.toString());
    }

    @Test
    void applicationFactoryKeepsTheRawFreeFormAppId() {
        ResourceMember member = ResourceMember.application("saml-rp");

        assertThat(member.memberType()).isEqualTo(MemberType.APPLICATION);
        assertThat(member.memberId()).isEqualTo("saml-rp");
    }

    @Test
    void applicationIdNeedNotBeAUuid() {
        assertThatCode(() -> ResourceMember.application("shop-client")).doesNotThrowAnyException();
    }
}
