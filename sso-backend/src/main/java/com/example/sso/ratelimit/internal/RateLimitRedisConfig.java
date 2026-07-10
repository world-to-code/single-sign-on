package com.example.sso.ratelimit.internal;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Redis connection Bucket4j stores its buckets in. Bucket4j needs a {@code String -> byte[]} Lettuce
 * connection, which Spring's {@code LettuceConnectionFactory} does not expose, so the rate limiter owns a
 * small dedicated client against the same Redis.
 *
 * <p>A bucket key expires once it has had time to refill to full: at that point its stored state is
 * indistinguishable from a fresh bucket, so dropping it costs nothing and Redis does not grow with every IP
 * that ever hit an auth endpoint.
 */
@Configuration
public class RateLimitRedisConfig {

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient(@Value("${spring.data.redis.host}") String host,
                                     @Value("${spring.data.redis.port}") int port) {
        return RedisClient.create(RedisURI.create(host, port));
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    ProxyManager<String> rateLimitBuckets(StatefulRedisConnection<String, byte[]> connection,
                                          @Value("${sso.ratelimit.window-seconds}") long windowSeconds) {
        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(windowSeconds)))
                .build();
    }
}
