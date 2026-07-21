package com.example.sso.config.internal;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code @ConfigurationProperties} records the modules declare.
 *
 * <p>Config is where wiring decisions belong, so the scan is declared here rather than on the bootstrap class
 * — that one should say what the application IS, not how its properties are bound.
 *
 * <p>The scan exists so a group of tunables that belong together can be ONE bound record instead of several
 * {@code @Value} parameters of the same type sitting side by side, which is the argument order nobody notices
 * getting swapped. Both styles are sanctioned by the config-tunables rule; a record is for the cases where the
 * values travel as a set, and it also fails at startup naming the property that is missing.
 *
 * <p>Scanned from the application's own base package, so a module declares a properties record next to the
 * code that reads it and nothing here changes.
 */
@Configuration
@ConfigurationPropertiesScan("com.example.sso")
public class ConfigurationPropertiesConfig {
}
