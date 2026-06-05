package com.nubeero.cia.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.common.tenant.MultiTenantConnectionProvider;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the runtime-pgcrypto-search-path fix: pgcrypto lives in {@code public}
 * (installed there by the tenant migrator), and a real tenant connection must be
 * able to call {@code pgp_sym_*} even though its primary schema is the tenant's.
 * Before the fix ({@code setSchema(tenant)} -> search_path = tenant only) this IT
 * fails with "function pgp_sym_encrypt does not exist".
 */
class MultiTenantConnectionProviderSearchPathIT extends TenantProvisioningItSupport {

    private static final String TENANT = "tenant_pgcrypto_it";

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT);
            st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        }
    }

    @Test
    void pgcryptoResolvesForNonPublicTenant() throws Exception {
        MultiTenantConnectionProvider provider = new MultiTenantConnectionProvider(dataSource());
        Connection conn = provider.getConnection(TENANT);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT pgp_sym_decrypt(pgp_sym_encrypt('secret','k'),'k')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("secret");
        } finally {
            provider.releaseConnection(TENANT, conn);
        }
    }

    @Test
    void searchPathIsTenantThenPublic() throws Exception {
        MultiTenantConnectionProvider provider = new MultiTenantConnectionProvider(dataSource());
        Connection conn = provider.getConnection(TENANT);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW search_path")) {
            assertThat(rs.next()).isTrue();
            String searchPath = rs.getString(1);
            assertThat(searchPath).contains(TENANT).contains("public");
            assertThat(searchPath.indexOf(TENANT)).isLessThan(searchPath.indexOf("public"));
        } finally {
            provider.releaseConnection(TENANT, conn);
        }
    }
}
