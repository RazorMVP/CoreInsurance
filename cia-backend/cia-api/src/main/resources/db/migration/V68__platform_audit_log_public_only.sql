-- V68: platform_audit_log is a public-only table. V67 introduced it as an unqualified
-- CREATE TABLE above the tenant baseline (baselineVersion=2), so the per-tenant Flyway
-- sweep cloned a dead copy into every tenant schema. Branch on current_schema():
--   * public run  -> relax target_schema to NULL (super-admin invite/revoke audit rows
--                    have no tenant schema)
--   * tenant run  -> drop the dead copy (explicitly schema-qualified so it can NEVER
--                    fall through search_path to public.platform_audit_log)
DO $$
BEGIN
  IF current_schema() = 'public' THEN
    ALTER TABLE public.platform_audit_log ALTER COLUMN target_schema DROP NOT NULL;
    COMMENT ON COLUMN public.platform_audit_log.action IS
      'ONBOARD | SUSPEND | ACTIVATE | INVITE_SUPER_ADMIN | REVOKE_SUPER_ADMIN';
  ELSE
    EXECUTE format('DROP TABLE IF EXISTS %I.platform_audit_log', current_schema());
  END IF;
END
$$;
