package com.example.sso.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Where the suite's Postgres and Redis live.
 *
 * <p>Two ways to get them, decided once per JVM:
 *
 * <ul>
 *   <li><b>A stack already running</b> — {@code docker compose -f docker-compose.testinfra.yml up -d}. Every
 *       Gradle fork attaches to the same pair, so one machine runs two containers no matter how many forks
 *       Gradle starts.</li>
 *   <li><b>Testcontainers</b>, when that stack is not reachable. Correct but per-JVM, so each fork pays for its
 *       own Postgres, Redis and reaper — which is why the compose stack exists. This is what CI uses, where a
 *       throwaway runner makes the cost irrelevant.</li>
 * </ul>
 *
 * <p>Either way the forks are isolated the same way: {@link AbstractIntegrationTest} gives each worker its own
 * database and its own Redis database index. Nothing here is shared but the server.
 *
 * <p>Detection is a TCP connect rather than a flag, so nobody has to remember to set one — start the stack and
 * it is used, stop it and the suite still passes. It is deliberately a probe of the TEST ports (55432/56379)
 * and never the dev ones: this class hands out a connection that databases get dropped through.
 */
final class TestInfrastructure {

    private static final String HOST = System.getProperty("sso.test.infra.host", "localhost");
    private static final int POSTGRES_PORT = Integer.getInteger("sso.test.infra.postgres-port", 55432);
    private static final int REDIS_PORT = Integer.getInteger("sso.test.infra.redis-port", 56379);

    /** Short: either it is up and local, or it is not there. A slow probe would just delay every fork. */
    private static final int PROBE_TIMEOUT_MILLIS = 300;

    private static final boolean EXTERNAL = reachable(POSTGRES_PORT) && reachable(REDIS_PORT);

    private static final PostgreSQLContainer POSTGRES = EXTERNAL ? null
            : new PostgreSQLContainer("postgres:17")
                    .withInitScript("testcontainers/create-runtime-role.sql");

    private static final GenericContainer<?> REDIS = EXTERNAL ? null
            : new GenericContainer<>("redis:7")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--notify-keyspace-events", "Egx");

    static {
        if (!EXTERNAL) {
            // Loud on purpose. This path is correct but expensive — one Postgres, one Redis and one reaper PER
            // FORK — and it used to be taken silently, so a stopped stack looked exactly like a running one.
            System.err.printf("%n[test-infra] %s:%d is not reachable, so this JVM is starting its OWN Postgres "
                    + "and Redis. Every fork will do the same. Start the shared stack with:%n"
                    + "    docker compose -f docker-compose.testinfra.yml up -d%n%n", HOST, POSTGRES_PORT);
            POSTGRES.start();
            REDIS.start();
        }
    }

    private TestInfrastructure() {
    }

    static String postgresHost() {
        return EXTERNAL ? HOST : POSTGRES.getHost();
    }

    static int postgresPort() {
        return EXTERNAL ? POSTGRES_PORT : POSTGRES.getFirstMappedPort();
    }

    /** The privileged owner: creates each fork's database, and the role Flyway migrates as. */
    static String adminUser() {
        return EXTERNAL ? "postgres" : POSTGRES.getUsername();
    }

    static String adminPassword() {
        return EXTERNAL ? "postgres" : POSTGRES.getPassword();
    }

    /** Admin URL for the server's default database — the one a fork connects to in order to create its own. */
    static String adminJdbcUrl() {
        return "jdbc:postgresql://" + postgresHost() + ":" + postgresPort() + "/postgres";
    }

    static String redisHost() {
        return EXTERNAL ? HOST : REDIS.getHost();
    }

    static int redisPort() {
        return EXTERNAL ? REDIS_PORT : REDIS.getMappedPort(6379);
    }

    private static boolean reachable(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(HOST, port), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException notThere) {
            return false;
        }
    }
}
