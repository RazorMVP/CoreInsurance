package com.nubeero.cia.tenant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TenantProvisioningService implements ActiveTenantMigrationService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantSchemaMigrator tenantSchemaMigrator;

    public TenantProvisioningService(JdbcTemplate jdbcTemplate, TenantSchemaMigrator tenantSchemaMigrator) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantSchemaMigrator = tenantSchemaMigrator;
    }

    public TenantProvisionResponse provision(TenantProvisionRequest request) {
        validateRequest(request);
        assertTenantDoesNotExist(request.schemaName(), request.subdomain());

        UUID tenantId = null;
        boolean schemaCreated = false;
        try {
            tenantId = insertInactiveTenant(request);
            createSchema(request.schemaName());
            schemaCreated = true;
            tenantSchemaMigrator.migrateTenantSchema(request.schemaName());
            activateTenant(tenantId);
            return new TenantProvisionResponse(
                    tenantId,
                    request.schemaName(),
                    request.subdomain(),
                    request.name(),
                    true
            );
        } catch (DuplicateKeyException ex) {
            cleanupFailedProvisioning(tenantId, request.schemaName(), schemaCreated);
            throw tenantConflict("Tenant schema or subdomain already exists", ex);
        } catch (TenantProvisioningException ex) {
            cleanupFailedProvisioning(tenantId, request.schemaName(), schemaCreated);
            throw ex;
        } catch (RuntimeException ex) {
            cleanupFailedProvisioning(tenantId, request.schemaName(), schemaCreated);
            throw new TenantProvisioningException(
                    "TENANT_PROVISIONING_FAILED",
                    "Tenant provisioning failed before activation",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ex
            );
        }
    }

    @Override
    public void migrateActiveTenants() {
        jdbcTemplate.queryForList(
                "SELECT schema_name FROM public.tenants WHERE active = true ORDER BY schema_name",
                String.class
        ).forEach(tenantSchemaMigrator::migrateTenantSchema);
    }

    private void validateRequest(TenantProvisionRequest request) {
        if (request == null
                || !TenantSchemaName.isSafeSchemaName(request.schemaName())
                || !TenantSchemaName.isSafeSubdomain(request.subdomain())
                || request.name() == null
                || request.name().isBlank()
                || request.name().length() > 255) {
            throw new TenantProvisioningException(
                    "TENANT_PROVISIONING_INVALID_REQUEST",
                    "Tenant schema, subdomain, and name are required and must be safe",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private void assertTenantDoesNotExist(String schemaName, String subdomain) {
        Integer matches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.tenants WHERE schema_name = ? OR subdomain = ?",
                Integer.class,
                schemaName,
                subdomain
        );
        if (matches != null && matches > 0) {
            throw tenantConflict("Tenant schema or subdomain already exists", null);
        }

        Integer schemaMatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class,
                schemaName
        );
        if (schemaMatches != null && schemaMatches > 0) {
            throw tenantConflict("Tenant schema already exists", null);
        }
    }

    private UUID insertInactiveTenant(TenantProvisionRequest request) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO public.tenants (schema_name, subdomain, name, active)
                VALUES (?, ?, ?, false)
                RETURNING id
                """,
                UUID.class,
                request.schemaName(),
                request.subdomain(),
                request.name()
        );
    }

    private void createSchema(String schemaName) {
        jdbcTemplate.execute("CREATE SCHEMA " + schemaName);
    }

    private void activateTenant(UUID tenantId) {
        jdbcTemplate.update(
                "UPDATE public.tenants SET active = true, updated_at = NOW() WHERE id = ?",
                tenantId
        );
    }

    private void cleanupFailedProvisioning(UUID tenantId, String schemaName, boolean schemaCreated) {
        if (schemaCreated && TenantSchemaName.isSafeSchemaName(schemaName)) {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
        }
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM public.tenants WHERE id = ? AND active = false", tenantId);
        }
    }

    private TenantProvisioningException tenantConflict(String message, Throwable cause) {
        return new TenantProvisioningException(
                "TENANT_PROVISIONING_CONFLICT",
                message,
                HttpStatus.CONFLICT,
                cause
        );
    }
}
