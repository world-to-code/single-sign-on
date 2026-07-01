package com.example.sso;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Enforces the modular-monolith boundaries: each direct sub-package of {@code com.example.sso} is an
 * application module, and this fails the build on illegal cross-module access or cyclic dependencies.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(SsoServerApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }
}
