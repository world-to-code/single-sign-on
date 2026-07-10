package com.example.sso.ratelimit.internal;

import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rate limiter opens its own Lettuce connection (Bucket4j needs a {@code String -> byte[]} codec that
 * Spring's connection factory does not expose). It must therefore carry the SAME credentials Boot resolved
 * for the session client: prod Redis mandates a password, and a limiter that authenticated with nothing
 * would NOAUTH on every login attempt — taking authentication down while the test suite, whose Redis has no
 * password, stayed green.
 */
class RateLimitRedisConfigTest {

    private final RateLimitRedisConfig config = new RateLimitRedisConfig();

    private DataRedisConnectionDetails connection(String username, String password) {
        return new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of("redis.internal", 6380, 3);
            }

            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public String getPassword() {
                return password;
            }
        };
    }

    @Test
    void carriesTheResolvedEndpointAndDatabase() {
        RedisURI uri = config.uriOf(connection(null, null));

        assertThat(uri.getHost()).isEqualTo("redis.internal");
        assertThat(uri.getPort()).isEqualTo(6380);
        assertThat(uri.getDatabase()).isEqualTo(3);
        assertThat(uri.getPassword()).isNull();
    }

    @Test
    void carriesAPasswordOnlyCredential() {
        RedisURI uri = config.uriOf(connection(null, "s3cret"));

        assertThat(uri.getPassword()).isEqualTo("s3cret".toCharArray());
        assertThat(uri.getUsername()).isNull();
    }

    @Test
    void carriesAnAclUsernameAndPassword() {
        RedisURI uri = config.uriOf(connection("sso", "s3cret"));

        assertThat(uri.getUsername()).isEqualTo("sso");
        assertThat(uri.getPassword()).isEqualTo("s3cret".toCharArray());
    }

    @Test
    void failsFastWithAClearMessageOnANonStandaloneTopology() {
        // A cluster/sentinel connection has no Standalone; deriving host/port off null would NPE at startup.
        DataRedisConnectionDetails clusterOnly = new DataRedisConnectionDetails() {
        };

        assertThatThrownBy(() -> config.uriOf(clusterOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("standalone");
    }
}
