import { afterEach, describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import {
  apiClient, validatedGet,
  BrokerDtoSchema, ApprovalGroupDtoSchema, AdjusterDtoSchema,
} from '@cia/api-client';

// ─── Regression guard: /api/v1/setup/* list-envelope shape ──────────────────
//
// The Setup list pages (Organisations tabs, Vehicle Registry, Claims Config,
// Products, Users, Access/Approval Groups, Classes, Clause Bank) were migrated
// off raw `apiClient.get(...).then(r => r.data.data)` onto `validatedGet` in the
// S4a-1 sweep. Setup list endpoints return the array DIRECTLY in `data`, with
// pagination in `meta` (the flat-list convention):
//
//   { "data": [ ...rows ], "meta": { total, page, size } }
//
// This test locks the flat-envelope contract at the parse layer — the same class
// of silent drift that white-screened the audit tabs. Importing the schemas here
// also loads @cia/api-client's setup module, so the ApprovalLevel→ApprovalGroup
// const ordering is exercised (a temporal-dead-zone throw would fail this file).

/** Mirrors the real backend envelope for a setup list endpoint. */
function envelope<T>(rows: T[]) {
  return { data: rows, meta: { total: rows.length, page: 0, size: 20, nextCursor: null, prevCursor: null } };
}

const brokerRow = {
  id: 'b1', name: 'Acme Brokers', code: 'ACME', rcNumber: 'RC123',
  licenseNumber: 'NAICOM/BR/001', address: '1 Marina', email: 'x@acme.ng',
  phone: '080', createdAt: '2026-01-01T00:00:00Z', updatedAt: null,
};
const approvalGroupRow = {
  id: 'ag1', name: 'Underwriting', entityType: 'POLICY',
  levels: [{ id: 'lv1', levelOrder: 1, approverUserId: 'u1', approverName: 'Ada', maxAmount: 5_000_000 }],
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
};
const adjusterRow = {
  id: 'ad1', name: 'Loss Experts', code: 'LX', type: 'EXTERNAL',
  licenseNumber: 'NAICOM/LA/007', email: null, phone: null, address: null,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: null,
};

afterEach(() => vi.restoreAllMocks());

describe('setup list endpoints — flat {data,meta} envelope', () => {
  it('validatedGet unwraps the flat array returned by /api/v1/setup/brokers', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: envelope([brokerRow, brokerRow]) });
    const rows = await validatedGet('/api/v1/setup/brokers', z.array(BrokerDtoSchema));
    expect(rows).toHaveLength(2);
    expect(rows[0].code).toBe('ACME');
  });

  it('a Page-shaped schema ({ content: [...] }) REJECTS the flat array — the drift the sweep fixes', () => {
    const pageShaped = z.object({ content: z.array(BrokerDtoSchema) });
    const result = pageShaped.safeParse(envelope([brokerRow]).data);
    expect(result.success).toBe(false);
  });

  it('parses the nested ApprovalGroup (levels[]) — const ordering loads without a TDZ throw', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: envelope([approvalGroupRow]) });
    const groups = await validatedGet('/api/v1/setup/approval-groups', z.array(ApprovalGroupDtoSchema));
    expect(groups).toHaveLength(1);
    expect(groups[0].levels[0].approverName).toBe('Ada');
  });

  it('parses an enum field (AdjusterDto.type) from /api/v1/setup/adjusters', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: envelope([adjusterRow]) });
    const adjusters = await validatedGet('/api/v1/setup/adjusters', z.array(AdjusterDtoSchema));
    expect(adjusters[0].type).toBe('EXTERNAL');
  });
});
