import { afterEach, describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import {
  apiClient, validatedGet,
  AuditLogDtoSchema, AuditAlertDtoSchema, LoginAuditLogDtoSchema,
} from '@cia/api-client';

// ─── Regression guard: /api/v1/audit/* list-envelope shape ──────────────────
//
// All audit list + report endpoints return the array DIRECTLY in `data`, with
// pagination in `meta` (the Session-77 flat-list convention):
//
//   { "data": [ ...rows ], "meta": { total, page, size } }
//
// The three audit tabs (AuditLogTab / LoginLogTab / AlertsTab) and ReportsTab
// used to parse `res.data.data` with a Spring-`Page` schema (`{ content: [] }`),
// which ALWAYS threw on the flat array → the queries errored → the tabs silently
// rendered fabricated mock rows (compliance surface showing fake audit trail).
// No IT or DTO-drift guard caught it — the field SETS matched; only the envelope
// SHAPE drifted. This test locks the flat-envelope contract at the parse layer.

/** Mirrors the real backend envelope for an audit list endpoint. */
function envelope<T>(rows: T[]) {
  return { data: rows, meta: { total: rows.length, page: 0, size: 20, nextCursor: null, prevCursor: null } };
}

// Fixtures mirror the exact key set each endpoint returns (verified against the
// live dev backend, 2026-07-01).
const auditRow = {
  id: 'a1', entityType: 'POLICY', entityId: 'p1', action: 'APPROVE',
  userId: 'u1', userName: 'e2e-user', timestamp: '2026-06-30T09:00:00Z',
  oldValue: null, newValue: null, ipAddress: '127.0.0.1', sessionId: 's1',
  approvalAmount: null, reason: null,
};
const alertRow = {
  id: 'al1', alertType: 'FAILED_LOGIN', severity: 'HIGH', userId: 'u1', userName: 'x',
  description: '3 failed logins', metadata: null, triggeredAt: '2026-06-30T09:00:00Z',
  acknowledged: false, acknowledgedBy: null, acknowledgedAt: null,
};
const loginRow = {
  id: 'l1', eventType: 'LOGIN', userId: 'u1', userName: 'x', ipAddress: '127.0.0.1',
  userAgent: 'UA', timestamp: '2026-06-30T09:00:00Z', success: true, failureReason: null,
};

afterEach(() => vi.restoreAllMocks());

describe('audit list endpoints — flat {data,meta} envelope', () => {
  it('validatedGet unwraps the flat array returned by /api/v1/audit/logs', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: envelope([auditRow, auditRow]) });
    const rows = await validatedGet('/api/v1/audit/logs', z.array(AuditLogDtoSchema));
    expect(rows).toHaveLength(2);
    expect(rows[0].action).toBe('APPROVE');
  });

  it('a Page-shaped schema ({ content: [...] }) REJECTS the flat array — the original bug', () => {
    // The old code did `pageSchema(AuditLogDtoSchema).parse(res.data.data)` where
    // res.data.data is the array itself — a Page schema can never match it.
    const pageShaped = z.object({ content: z.array(AuditLogDtoSchema) });
    const result = pageShaped.safeParse(envelope([auditRow]).data);
    expect(result.success).toBe(false);
  });

  it('validatedGet unwraps the alerts + login-logs envelopes too', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: envelope([alertRow]) });
    const alerts = await validatedGet('/api/v1/audit/alerts', z.array(AuditAlertDtoSchema));
    expect(alerts).toHaveLength(1);
    expect(alerts[0].alertType).toBe('FAILED_LOGIN');

    vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: envelope([loginRow]) });
    const logins = await validatedGet('/api/v1/audit/login-logs', z.array(LoginAuditLogDtoSchema));
    expect(logins).toHaveLength(1);
    expect(logins[0].eventType).toBe('LOGIN');
  });
});
