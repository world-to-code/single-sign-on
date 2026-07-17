package com.example.sso.shared.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link DeviceLabeler}: a pure, display-only heuristic that turns a User-Agent into a
 * "Browser on OS" label. Asserts on the returned string (no interactions to verify) and pins the
 * ordering-sensitive branches (Edge before Chrome, Chrome excludes Chromium, Safari excludes Chrome).
 */
class DeviceLabelerTest {

    private DeviceLabeler labeler;

    @BeforeEach
    void setUp() {
        labeler = new DeviceLabeler();
    }

    @Test
    void nullOrBlankUserAgentIsUnknownDevice() {
        assertThat(labeler.label(null)).isEqualTo("Unknown device");
        assertThat(labeler.label("   ")).isEqualTo("Unknown device");
    }

    @Test
    void chromeOnWindows() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/120.0 Safari/537.36";
        assertThat(labeler.label(ua)).isEqualTo("Chrome on Windows");
    }

    @Test
    void edgeIsDetectedBeforeChrome() {
        String ua = "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0";
        assertThat(labeler.label(ua)).isEqualTo("Edge on Windows");
    }

    @Test
    void safariOnMacExcludesChrome() {
        String ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "Version/17.0 Safari/605.1.15";
        assertThat(labeler.label(ua)).isEqualTo("Safari on macOS");
    }

    @Test
    void firefoxOnLinux() {
        String ua = "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0";
        assertThat(labeler.label(ua)).isEqualTo("Firefox on Linux");
    }

    @Test
    void iPhoneReportsIos() {
        // A GENUINE iPhone UA contains "like Mac OS X" — it must still resolve to iOS (regression guard:
        // iOS is checked before the macOS branch).
        String ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
        assertThat(labeler.label(ua)).endsWith("on iOS");
    }

    @Test
    void unrecognisedUserAgentFallsBackToBrowserAndUnknownOs() {
        assertThat(labeler.label("SomeExoticClient/1.0")).isEqualTo("Browser on Unknown OS");
    }
}
