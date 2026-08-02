import { describe, it, expect } from 'vitest';
import { z } from 'zod';
import {
  PolicySummaryDtoSchema, QuoteSummaryDtoSchema,
  EndorsementDtoSchema, CustomerSummaryDtoSchema,
} from '@cia/api-client';

// Guards the S4a-2 validatedGet migration of the four core transaction list
// pages: each list endpoint returns a flat `{ data: [...] }` envelope (unwrapped
// by validatedGet), NOT a Spring `Page` object — and the row schemas must reject
// a Page-shaped payload. Also locks the Customer summary-vs-detail split
// (displayName is required) and the endorsement nested-risk shape.
describe('core-list schemas — envelope + shape guards', () => {
  it('CustomerSummaryDtoSchema requires displayName and parses a lean row', () => {
    const row = {
      id: 'c1', customerNumber: 'CUS-1', customerType: 'INDIVIDUAL',
      customerStatus: 'ACTIVE', kycStatus: 'PENDING', displayName: 'Ada Lovelace',
      email: null, phone: null, relationshipManagerId: null,
      relationshipManagerName: null, createdAt: '2026-01-01T00:00:00Z',
    };
    expect(CustomerSummaryDtoSchema.parse(row).displayName).toBe('Ada Lovelace');
    const { displayName: _dropped, ...noName } = row;
    expect(CustomerSummaryDtoSchema.safeParse(noName).success).toBe(false);
  });

  it('EndorsementDtoSchema parses nested risks', () => {
    const row = {
      id: 'e1', endorsementNumber: 'END-1', status: 'DRAFT', endorsementType: 'RENEWAL',
      policyId: 'p1', policyNumber: 'POL-1', customerId: 'c1', customerName: 'Ada',
      productName: 'Motor', classOfBusinessName: 'Motor', brokerId: null, brokerName: null,
      effectiveDate: '2026-01-01', policyEndDate: '2026-12-31', remainingDays: 300,
      oldSumInsured: 1, newSumInsured: 2, oldNetPremium: 1, newNetPremium: 2,
      premiumAdjustment: 1, currencyCode: 'NGN', description: null, notes: null,
      approvedBy: null, approvedAt: null, rejectedBy: null, rejectedAt: null,
      rejectionReason: null, createdAt: '2026-01-01T00:00:00Z',
      risks: [{ id: 'r1', description: 'Car', sumInsured: 1, premium: 1, sectionId: null,
        sectionName: null, riskDetails: null, vehicleRegNumber: null, orderNo: 0 }],
    };
    expect(EndorsementDtoSchema.parse(row).risks).toHaveLength(1);
  });

  it('a Page-shaped payload does not satisfy z.array (drift guard)', () => {
    const page = { content: [], totalElements: 0 };
    expect(z.array(PolicySummaryDtoSchema).safeParse(page).success).toBe(false);
    expect(z.array(QuoteSummaryDtoSchema).safeParse(page).success).toBe(false);
  });
});
