package com.example.sso.config.internal;

import com.example.sso.session.internal.lifecycle.application.SessionManagerImpl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.ConfigureNotifyKeyspaceEventsAction;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;

/**
 * Stores HTTP sessions in Redis. Uses the INDEXED repository (not the plain one) for two reasons the
 * back-channel-logout feature depends on:
 * <ul>
 *   <li>a session's Redis key TTL expiry publishes a {@code SessionExpiredEvent} <b>without a request</b>
 *       (via keyspace notifications) — how idle-expiry is caught proactively, and</li>
 *   <li>the principal-name index backs concurrent-session control (see {@code SessionManagerImpl}).</li>
 * </ul>
 * Requires Redis keyspace notifications ({@code notify-keyspace-events Egx}); by default Spring Session
 * enables them at startup via a {@code CONFIG SET}. Managed Redis that forbids {@code CONFIG SET} must set
 * {@code sso.session.redis.configure-keyspace-events=false} and enable them in the server config instead.
 */
@Configuration
@EnableRedisIndexedHttpSession
public class RedisSessionConfig {

    /**
     * Disable Spring Session's startup {@code CONFIG SET notify-keyspace-events} when the deployment's
     * Redis forbids runtime config (managed offerings) — keyspace events must then be enabled server-side.
     */
    @Bean
    ConfigureRedisAction configureRedisAction(
            @Value("${sso.session.redis.configure-keyspace-events:true}") boolean configureKeyspaceEvents) {
        return configureKeyspaceEvents ? new ConfigureNotifyKeyspaceEventsAction() : ConfigureRedisAction.NO_OP;
    }
}
