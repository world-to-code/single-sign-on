package com.example.sso.ratelimit.internal;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Redis connection Bucket4j stores its buckets in. Bucket4j needs a {@code String -> byte[]} Lettuce
 * connection, which Spring's {@code LettuceConnectionFactory} does not expose, so the rate limiter owns a
 * small dedicated client against the same Redis.
 *
 * <p>It is built from Boot's resolved {@link DataRedisConnectionDetails} — never from a second reading of
 * {@code spring.data.redis.host/port} — so the credentials and TLS of the session client cannot drift from
 * the limiter's. Prod Redis mandates a password (it holds serialized SecurityContexts); a client that
 * ignored it would NOAUTH on every login attempt and take authentication down.
 *
 * <p>A bucket key expires once it has had time to refill to full: at that point its stored state is
 * indistinguishable from a fresh bucket, so dropping it costs nothing and Redis does not grow with every IP
 * that ever hit an auth endpoint.
 */
@Configuration
public class RateLimitRedisConfig {

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient(DataRedisConnectionDetails redis) {
        RedisClient client = RedisClient.create(uriOf(redis));
        SslBundle bundle = redis.getSslBundle();
        if (bundle != null) {
            // Install the SAME trust/key material Boot resolved for the session client, not the JVM default
            // trust store — else a private-CA Redis handshake fails and every rate-limited auth endpoint 500s.
            client.setOptions(ClientOptions.builder()
                    .sslOptions(SslOptions.builder()
                            .jdkSslProvider()
                            .trustManager(bundle.getManagers().getTrustManagerFactory())
                            .keyManager(bundle.getManagers().getKeyManagerFactory())
                            .build())
                    .build());
        }
        return client;
    }

    /** The same endpoint and credentials Boot resolved for the session client. */
    RedisURI uriOf(DataRedisConnectionDetails redis) {
        DataRedisConnectionDetails.Standalone standalone = redis.getStandalone();
        if (standalone == null) {
            // The limiter only supports a standalone Redis today; a cluster/sentinel deployment would need
            // its own topology wiring. Fail fast with a clear message rather than NPE on getHost().
            throw new IllegalStateException(
                    "Rate-limit Redis requires a standalone connection; cluster/sentinel is not supported");
        }
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(standalone.getHost())
                .withPort(standalone.getPort())
                .withDatabase(standalone.getDatabase())
                .withSsl(redis.getSslBundle() != null);
        String password = redis.getPassword();
        if (password != null) {
            char[] secret = password.toCharArray();
            // ACL user + password, or the legacy password-only AUTH.
            if (redis.getUsername() != null) {
                uri.withAuthentication(redis.getUsername(), secret);
            } else {
                uri.withPassword(secret);
            }
        }
        return uri.build();
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    ProxyManager<String> rateLimitBuckets(StatefulRedisConnection<String, byte[]> connection,
                                          @Value("${sso.ratelimit.window-seconds}") long windowSeconds) {
        return Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(windowSeconds)))
                .build();
    }
}
