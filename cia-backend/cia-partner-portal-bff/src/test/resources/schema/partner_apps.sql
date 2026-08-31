-- Byte-identical (partner_apps table only) copy of the CREATE TABLE statement in
-- cia-api/src/main/resources/db/migration/V12__create_partner_tables.sql, for
-- PortalAppsIT to create the table directly inside a tenant schema via plain JDBC (this
-- module cannot depend on cia-api, which owns Flyway and the real migration file — the
-- dependency direction runs the other way). Run with search_path already set to the target
-- tenant schema (unqualified table name, exactly like the real migration when it runs against a
-- tenant schema — see CLAUDE.md §5.4's migration-not-edited note: V12 is one of the migrations
-- that re-creates this table, unqualified, inside every tenant schema).
--
-- Keep in sync with V12 + the PartnerApp entity (cia-partner-api) whenever either changes.
CREATE TABLE IF NOT EXISTS partner_apps (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    client_id         VARCHAR(100) NOT NULL,
    app_name          VARCHAR(200) NOT NULL,
    contact_email     VARCHAR(200) NOT NULL,
    scopes            TEXT        NOT NULL DEFAULT '',
    plan              VARCHAR(20)  NOT NULL DEFAULT 'STARTER',
    rate_limit_rpm    INT         NOT NULL DEFAULT 60,
    allowed_ips       TEXT,
    active            BOOLEAN     NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        VARCHAR(100),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT pk_partner_apps PRIMARY KEY (id),
    CONSTRAINT uq_partner_apps_client_id UNIQUE (client_id)
);
CREATE INDEX IF NOT EXISTS idx_partner_apps_client_id ON partner_apps (client_id);
