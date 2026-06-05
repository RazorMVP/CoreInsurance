package com.nubeero.cia.common.tenant;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class MultiTenantConnectionProvider
        implements org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    public MultiTenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        // Security boundary: the identifier is interpolated into the SET statement
        // (schema names cannot be bound as JDBC parameters). It originates from the
        // validated JWT realm, but we validate again for defence-in-depth.
        TenantSchemas.validate(tenantIdentifier);
        Connection connection = getAnyConnection();
        try (Statement st = connection.createStatement()) {
            // Tenant schema first so its tables resolve; public last so shared
            // extensions (pgcrypto: pgp_sym_encrypt/decrypt) and the registry
            // resolve for every tenant. setSchema(tenant) alone would set the
            // search_path to the tenant only, breaking NDPR PII encryption.
            st.execute("SET search_path TO \"" + tenantIdentifier + "\", public");
            return connection;
        } catch (SQLException e) {
            // Don't leak the borrowed connection back to the caller's exception path.
            releaseAnyConnection(connection);
            throw e;
        }
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        // Defensive reset: getAnyConnection() callers borrow without setting a
        // search_path, so return connections to a known-clean state. getConnection
        // re-sets it on every borrow regardless. try/finally guarantees the
        // connection returns to the pool even if the reset SET fails.
        try (Statement st = connection.createStatement()) {
            st.execute("SET search_path TO public");
        } finally {
            releaseAnyConnection(connection);
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        throw new UnsupportedOperationException("Cannot unwrap as " + unwrapType.getName());
    }
}
