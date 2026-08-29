-- Cross-tenant registry (public schema) mapping a partner human developer to the
-- Partner App(s) they may manage. partner_app_id is a soft cross-schema reference
-- (the app row lives in a tenant schema) — no DB FK, registry-style like public.tenants.
CREATE TABLE IF NOT EXISTS public.partner_portal_grant (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_user_id    UUID         NOT NULL,
    partner_user_email VARCHAR(255) NOT NULL,
    tenant_schema      VARCHAR(63)  NOT NULL,
    partner_app_id     UUID         NOT NULL,
    role               VARCHAR(16)  NOT NULL,          -- MANAGER | VIEWER
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         VARCHAR(255),
    deleted_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ppg_user_app
    ON public.partner_portal_grant (partner_user_id, tenant_schema, partner_app_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ppg_user ON public.partner_portal_grant (partner_user_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ppg_app  ON public.partner_portal_grant (partner_app_id)  WHERE deleted_at IS NULL;
