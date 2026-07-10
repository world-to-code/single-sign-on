package com.example.sso.config.internal;

import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;

/**
 * Makes a failed {@code void @Async} method observable: its exception never reaches a caller, so it must be
 * logged here or the failure is silent. Logs the failed METHOD at ERROR — argument values are deliberately
 * NOT logged, because async payloads can carry PII (e.g. onboarding contact details, A09); a listener that
 * needs a correlating id in the log catches and logs it itself.
 */
@Slf4j
class LoggingAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {

    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        log.error("@Async {}.{} failed", method.getDeclaringClass().getSimpleName(), method.getName(), ex);
    }
}
