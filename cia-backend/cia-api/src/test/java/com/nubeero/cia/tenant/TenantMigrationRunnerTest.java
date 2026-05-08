package com.nubeero.cia.tenant;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TenantMigrationRunnerTest {

    @Test
    void migratesActiveTenantsWithoutClosingNormalApplicationContext() {
        TestActiveTenantMigrationService migrationService = new TestActiveTenantMigrationService();
        AtomicBoolean closed = new AtomicBoolean(false);

        new TenantMigrationRunner(migrationService, () -> closed.set(true), false).run(null);

        assertThat(migrationService.migrated).isTrue();
        assertThat(closed).isFalse();
    }

    @Test
    void closesContextAfterMigrationOnlyRun() {
        TestActiveTenantMigrationService migrationService = new TestActiveTenantMigrationService();
        AtomicBoolean closed = new AtomicBoolean(false);

        new TenantMigrationRunner(migrationService, () -> closed.set(true), true).run(null);

        assertThat(migrationService.migrated).isTrue();
        assertThat(closed).isTrue();
    }

    private static final class TestActiveTenantMigrationService implements ActiveTenantMigrationService {

        private boolean migrated;

        @Override
        public void migrateActiveTenants() {
            migrated = true;
        }
    }
}
