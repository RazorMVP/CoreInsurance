import { describe, it, expect } from 'vitest';
import {
  TenantSummarySchema,
  PlatformAuditEntrySchema,
  TenantDetailSchema,
  OnboardTenantResponseSchema,
  TenantStatsSchema,
  SuperAdminSummarySchema,
  InviteSuperAdminResponseSchema,
} from './platform';

describe('platform schemas', () => {
  it('parses a tenant summary', () => {
    const t = TenantSummarySchema.parse({
      schema: 'tenant_acme', displayName: 'Acme', subdomain: 'acme',
      active: true, createdAt: '2026-06-10T00:00:00Z',
    });
    expect(t.schema).toBe('tenant_acme');
  });

  it('accepts a NULL target_schema audit row (super-admin action)', () => {
    const e = PlatformAuditEntrySchema.parse({
      id: 'x', action: 'INVITE_SUPER_ADMIN', targetSchema: null,
      actorUsername: 'root', actorRealm: 'platform', detail: '{"username":"x"}',
      sourceIp: '1.1.1.1', at: '2026-06-10T00:00:00Z',
    });
    expect(e.targetSchema).toBeNull();
  });

  it('parses detail, onboard response, stats, super-admin, invite response', () => {
    const tenant = { schema: 't', displayName: 'd', subdomain: 's', active: true, createdAt: '2026-06-10T00:00:00Z' };
    expect(TenantDetailSchema.parse({ tenant, recentAudit: [] }).recentAudit).toEqual([]);
    expect(OnboardTenantResponseSchema.parse({ tenant, firstAdmin: { username: 'a', email: 'a@x.test', temporaryPassword: 'Aa1!x' } }).firstAdmin.temporaryPassword).toBe('Aa1!x');
    expect(TenantStatsSchema.parse({ total: 12, active: 10, suspended: 2 }).suspended).toBe(2);
    expect(SuperAdminSummarySchema.parse({ username: 'r', email: 'r@x.test', enabled: true }).enabled).toBe(true);
    expect(InviteSuperAdminResponseSchema.parse({ username: 's', email: 's@x.test', temporaryPassword: 'Aa1!y' }).username).toBe('s');
  });
});
