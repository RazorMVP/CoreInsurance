-- Byte-identical copy of cia-api/src/main/resources/db/migration/V1__create_public_schema.sql's
-- tenants table, for PortalAppsIT to create + seed via plain JDBC (this module cannot depend on
-- cia-api — the dependency direction runs the other way; see partner_apps.sql's header for the
-- same rationale). Used by PortalTenantLabelLookup's tenantLabel enrichment.
CREATE TABLE IF NOT EXISTS public.tenants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schema_name VARCHAR(63)  NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    subdomain   VARCHAR(63)  NOT NULL UNIQUE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tenants_subdomain ON public.tenants (subdomain);
CREATE INDEX IF NOT EXISTS idx_tenants_active    ON public.tenants (active);
