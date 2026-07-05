package com.example.sso.tenancy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link OrgContext}: the three states and the save/restore nesting semantics. */
class OrgContextTest {

    private final OrgContext context = new OrgContext();

    @Test
    void isUnsetByDefault() {
        assertThat(context.currentOrg()).isEmpty();
        assertThat(context.isPlatform()).isFalse();
    }

    @Test
    void callInOrgBindsTheOrgAndRestoresAfterwards() {
        UUID org = UUID.randomUUID();

        String result = context.callInOrg(org, () -> {
            assertThat(context.currentOrg()).contains(org);
            assertThat(context.isPlatform()).isFalse();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(context.currentOrg()).isEmpty(); // restored to unset
    }

    @Test
    void callAsPlatformSetsPlatformAndRestoresAfterwards() {
        context.callAsPlatform(() -> {
            assertThat(context.isPlatform()).isTrue();
            assertThat(context.currentOrg()).isEmpty();
            return null;
        });

        assertThat(context.isPlatform()).isFalse();
    }

    @Test
    void nestedScopesRestoreTheOuterContext() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();

        context.runInOrg(outer, () -> {
            context.runAsPlatform(() -> assertThat(context.isPlatform()).isTrue());
            assertThat(context.currentOrg()).contains(outer); // outer restored after the platform scope
            context.runInOrg(inner, () -> assertThat(context.currentOrg()).contains(inner));
            assertThat(context.currentOrg()).contains(outer); // outer restored after the inner org scope
        });

        assertThat(context.currentOrg()).isEmpty();
    }

    @Test
    void contextIsRestoredEvenWhenTheActionThrows() {
        UUID org = UUID.randomUUID();

        assertThatThrownBy(() -> context.runInOrg(org, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(context.currentOrg()).isEmpty(); // restored despite the exception
    }
}
