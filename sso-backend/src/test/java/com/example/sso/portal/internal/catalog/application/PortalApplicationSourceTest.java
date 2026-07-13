package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationDescriptor;
import com.example.sso.portal.binding.PortalApps;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PortalApplicationSource} publishes exactly the end-user portal as a system {@code PORTAL}/{@code user}
 * application — tier-independent (every tenant shares the one portal) and never the admin console (that stays an
 * OIDC app).
 */
class PortalApplicationSourceTest {

    @Test
    void publishesTheUserPortalAsASystemPortalApp() {
        List<ApplicationDescriptor> apps = new PortalApplicationSource().applications();

        assertThat(apps).singleElement().satisfies(app -> {
            assertThat(app.type()).isEqualTo(AppType.PORTAL);
            assertThat(app.id()).isEqualTo(PortalApps.USER);
            assertThat(app.name()).isEqualTo("User Portal");
            assertThat(app.system()).isTrue();  // platform-managed: not editable/deletable, not assignable
            assertThat(app.launchUrl()).isEqualTo("/");
        });
    }
}
