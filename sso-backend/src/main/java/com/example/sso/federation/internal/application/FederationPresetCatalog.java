package com.example.sso.federation.internal.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Serves the data-defined provider presets to the admin console. Thin over {@link FederationPresetProperties}:
 * the presets are static vendor config, so there is no per-request work — but the seam keeps the controller
 * from binding config directly, and is where any future shaping (hiding a preset, per-tenant overrides) lands.
 */
@Service
@RequiredArgsConstructor
public class FederationPresetCatalog {

    private final FederationPresetProperties properties;

    /** Every configured preset, in declaration order; empty when none are configured. */
    public List<FederationPresetView> list() {
        return properties.presets();
    }
}
