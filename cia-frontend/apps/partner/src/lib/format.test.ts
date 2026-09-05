import { describe, it, expect } from 'vitest';
import { formatNaira, formatInt, formatPercent, formatDate, formatTimestamp } from './format';

const DASH = '—';

describe('format helpers — null tolerance', () => {
  it.each([
    ['formatNaira', formatNaira],
    ['formatInt', formatInt],
    ['formatPercent', formatPercent],
  ] as const)('%s renders — for null/undefined/NaN', (_name, fn) => {
    expect(fn(null)).toBe(DASH);
    expect(fn(undefined)).toBe(DASH);
    expect(fn(Number.NaN)).toBe(DASH);
  });

  it.each([
    ['formatDate', formatDate],
    ['formatTimestamp', formatTimestamp],
  ] as const)('%s renders — for null/undefined/empty/invalid', (_name, fn) => {
    expect(fn(null)).toBe(DASH);
    expect(fn(undefined)).toBe(DASH);
    expect(fn('')).toBe(DASH);
    expect(fn('not-a-date')).toBe(DASH);
  });
});

describe('format helpers — real values', () => {
  it('formatNaira renders a ₦ amount with two decimals', () => {
    const out = formatNaira(1500);
    expect(out.startsWith('₦')).toBe(true);
    expect(out).toContain('1,500.00');
  });
  it('formatInt renders a grouped integer', () => {
    expect(formatInt(1500)).toBe('1,500');
  });
  it('formatPercent renders one decimal place', () => {
    expect(formatPercent(0.1234)).toBe('12.3%');
    expect(formatPercent(0)).toBe('0.0%');
  });
  it('formatDate renders a valid ISO date', () => {
    expect(formatDate('2026-08-20T00:00:00Z')).not.toBe(DASH);
  });
  it('formatTimestamp renders a valid ISO timestamp', () => {
    expect(formatTimestamp('2026-08-20T14:03:00Z')).not.toBe(DASH);
  });
});
