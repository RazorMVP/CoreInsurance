import { describe, it, expect } from 'vitest';
import { formatPercent, formatInt, formatDate } from './format';

describe('format helpers', () => {
  it('renders em-dash for null', () => {
    expect(formatPercent(null)).toBe('—');
    expect(formatInt(undefined)).toBe('—');
    expect(formatDate('')).toBe('—');
  });
  it('formats a percent and an int', () => {
    expect(formatPercent(0.1234)).toBe('12.3%');
    expect(formatInt(1500)).toBe('1,500');
  });
});
