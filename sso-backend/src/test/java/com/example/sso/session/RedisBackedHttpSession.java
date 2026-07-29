package com.example.sso.session;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import org.springframework.session.Session;

/**
 * A servlet {@link HttpSession} whose attributes are a real Spring Session's attributes.
 *
 * <p>Spring Session's own wrapper is package-private, so a test that wants to call production code taking an
 * {@code HttpSession} and have the writes land in Redis needs this. Only what such a test exercises is
 * implemented; the container-owned parts throw, so a caller that starts depending on them fails loudly rather
 * than quietly getting a lie.
 */
class RedisBackedHttpSession implements HttpSession {

    private final Session session;

    RedisBackedHttpSession(Session session) {
        this.session = session;
    }

    @Override
    public String getId() {
        return session.getId();
    }

    @Override
    public Object getAttribute(String name) {
        return session.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        session.setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        session.removeAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(session.getAttributeNames());
    }

    @Override
    public long getCreationTime() {
        return session.getCreationTime().toEpochMilli();
    }

    @Override
    public long getLastAccessedTime() {
        return session.getLastAccessedTime().toEpochMilli();
    }

    @Override
    public int getMaxInactiveInterval() {
        return (int) session.getMaxInactiveInterval().getSeconds();
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        session.setMaxInactiveInterval(Duration.ofSeconds(interval));
    }

    @Override
    public ServletContext getServletContext() {
        throw new UnsupportedOperationException("no servlet container in this test");
    }

    @Override
    public void invalidate() {
        throw new UnsupportedOperationException("delete through the repository instead");
    }

    @Override
    public boolean isNew() {
        throw new UnsupportedOperationException("not modelled");
    }
}
