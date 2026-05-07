package com.nubeero.cia.common.tenant;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

@Component
public class MultiTenantConnectionProvider
        implements org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;
    private static final Pattern SAFE_SCHEMA_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

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
        if (!isSafeSchemaName(tenantIdentifier)) {
            throw new SQLException("Unsafe tenant schema name");
        }
        Connection connection = getAnyConnection();
        connection.setSchema(tenantIdentifier);
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        connection.setSchema("public");
        releaseAnyConnection(connection);
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

    private boolean isSafeSchemaName(String tenantIdentifier) {
        return tenantIdentifier != null && SAFE_SCHEMA_NAME.matcher(tenantIdentifier).matches();
    }
}
