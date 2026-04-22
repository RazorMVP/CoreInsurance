-- Public schema: tenant registry and platform metadata.
-- All tables here are cross-tenant. Business data lives in per-tenant schemas.

CREATE TABLE IF NOT EXISTS tenants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schema_name VARCHAR(63)  NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    subdomain   VARCHAR(63)  NOT NULL UNIQUE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_subdomain ON tenants (subdomain);
CREATE INDEX idx_tenants_active    ON tenants (active);
