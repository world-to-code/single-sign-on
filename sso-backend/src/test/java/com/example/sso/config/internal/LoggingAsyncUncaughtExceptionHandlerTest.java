package com.example.sso.config.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link LoggingAsyncUncaughtExceptionHandler}: a {@code void @Async} failure is observable
 * only through this handler, so the ERROR line must identify the failed method and keep the stack trace —
 * but must NOT contain argument values (async payloads can carry PII, e.g. onboarding contact details).
 */
class LoggingAsyncUncaughtExceptionHandlerTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingAsyncUncaughtExceptionHandler.class);

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void logsTheFailedMethodAtErrorWithoutArgumentValues() throws Exception {
        Method method = Sample.class.getDeclaredMethod("provision", UUID.class);
        UUID orgId = UUID.randomUUID();

        new LoggingAsyncUncaughtExceptionHandler()
                .handleUncaughtException(new IllegalStateException("boom"), method, orgId);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("Sample").contains("provision")
                    .doesNotContain(orgId.toString());
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
        });
    }

    private static class Sample {
        @SuppressWarnings("unused")
        void provision(UUID orgId) {
        }
    }
}
