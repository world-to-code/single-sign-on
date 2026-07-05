package com.example.sso.tenancy.internal;

import com.example.sso.tenancy.OrgContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Wraps the pooled DataSource so every borrowed connection carries the current {@link OrgContext} as the
 * Postgres RLS GUCs {@code app.current_org} / {@code app.platform}. Both are (re)set on EVERY acquisition —
 * to the active value or to empty — so a value left on a pooled physical connection by a prior borrow is
 * always overwritten, never leaked. Empty/absent → the RLS policy matches no row (fail-closed).
 *
 * <p><b>Load-bearing invariant:</b> Hibernate acquires the JDBC connection lazily on the first statement and
 * holds it for the transaction ({@code hibernate.connection.handling_mode} is pinned to
 * DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION in application.yml). So the connection is first borrowed
 * AFTER a service method has entered its {@code OrgContext.runInOrg(...)} scope, and the same connection
 * carries the flush at commit — the GUC set here reflects the operation's tenant, not the ambient request
 * context. A RELEASE_AFTER_STATEMENT handling mode would re-borrow at flush with the context already
 * restored and break this; do not change it.
 */
public class OrgAwareDataSource extends DelegatingDataSource {

    private static final String SET_CONTEXT =
            "select set_config('app.current_org', ?, false), set_config('app.platform', ?, false)";

    private final OrgContext orgContext;

    public OrgAwareDataSource(DataSource target, OrgContext orgContext) {
        super(target);
        this.orgContext = orgContext;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return apply(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return apply(super.getConnection(username, password));
    }

    private Connection apply(Connection connection) throws SQLException {
        boolean platform = orgContext.isPlatform();
        String org = (!platform && orgContext.currentOrg().isPresent())
                ? orgContext.currentOrg().get().toString() : "";
        try (PreparedStatement ps = connection.prepareStatement(SET_CONTEXT)) {
            ps.setString(1, org);
            ps.setString(2, platform ? "on" : "");
            ps.execute();
        } catch (SQLException e) {
            connection.close(); // don't leak the borrowed connection back to nowhere
            throw e;
        }
        return connection;
    }
}
