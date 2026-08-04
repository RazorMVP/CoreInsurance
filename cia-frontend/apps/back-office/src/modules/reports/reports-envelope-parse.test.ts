import { describe, it, expect } from 'vitest';
import { z } from 'zod';
import {
  ReportDefinitionSchema,
  ReportAccessPolicySchema,
} from './types/report.types';

const validDefinition = {
  id: 'r1',
  name: 'Loss Ratio by Class',
  description: 'x',
  category: 'CUSTOMER',
  type: 'SYSTEM',
  dataSource: 'UNDERWRITING_PERFORMANCE',
  config: {
    fields: [{ key: 'class', label: 'Class', type: 'STRING', computed: false }],
    filters: [{ key: 'date_from', label: 'From', type: 'DATE', required: false }],
    chart: { type: 'TABLE_ONLY' },
  },
  pinnable: true,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
};

describe('reports envelope + shape contract', () => {
  it('validatedGet-shaped array of ReportDefinition parses', () => {
    const parsed = z.array(ReportDefinitionSchema).parse([validDefinition]);
    expect(parsed[0].dataSource).toBe('UNDERWRITING_PERFORMANCE');
    expect(parsed[0].config.fields[0].type).toBe('STRING');
  });

  it('rejects a Spring Page-shaped envelope where a flat array is expected', () => {
    const pageShaped = { content: [validDefinition], totalElements: 1 };
    expect(() => z.array(ReportDefinitionSchema).parse(pageShaped)).toThrow();
  });

  it('rejects an unknown dataSource enum value (drift-catch)', () => {
    expect(() => ReportDefinitionSchema.parse({ ...validDefinition, dataSource: 'NOT_A_SOURCE' })).toThrow();
  });

  it('ReportAccessPolicy parses with an optional nested report + without it', () => {
    const base = { id: 'p1', accessGroupId: 'g1', canView: true, canExportCsv: false, canExportPdf: false };
    expect(ReportAccessPolicySchema.parse(base).report).toBeUndefined();
    expect(ReportAccessPolicySchema.parse({ ...base, category: 'FINANCE', report: validDefinition }).report?.id).toBe('r1');
  });
});
