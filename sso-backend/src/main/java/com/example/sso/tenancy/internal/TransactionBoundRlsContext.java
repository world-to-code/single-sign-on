package com.example.sso.tenancy.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pushes the RLS GUCs ({@code app.current_org} / {@code app.platform}) onto the connection ALREADY HELD by
 * the current JPA transaction, so an {@code OrgContext} scope change made AFTER the transaction borrowed its
 * connection still takes effect.
 *
 * <p>{@code OrgAwareDataSource} only sets the GUCs when a connection is first acquired. Under
 * {@code open-in-view:false}, a JPA {@code @Transactional} method borrows one connection at the start of the
 * transaction (under whatever context is bound then — the request context, or none) and holds it for the
 * whole transaction. A {@code callInOrg}/{@code callAsPlatform} entered INSIDE that method changes the
 * thread's context but not the frozen connection GUC, so its reads/writes would run against the wrong tenant
 * (the tenant-first login membership check, the seeder, a cross-org admin read). This re-applies the GUCs to
 * the held connection on every context change; when no transaction is active it is a no-op (the next fresh
 * acquisition applies the context via {@code OrgAwareDataSource}).
 */
@Component
public class TransactionBoundRlsContext {

    private static final String SET_CONTEXT =
            "select set_config('app.current_org', ?, false), set_config('app.platform', ?, false)";

    @PersistenceContext
    private EntityManager entityManager;

    public void apply(boolean platform, String orgId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return; // no held connection — OrgAwareDataSource sets the GUC on the next acquisition
        }
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SET_CONTEXT)) {
                ps.setString(1, orgId);
                ps.setString(2, platform ? "on" : "");
                ps.execute();
            }
        });
    }
}
