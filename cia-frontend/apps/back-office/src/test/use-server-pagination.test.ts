import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import React from 'react';
import { useServerPagination } from '@/lib/use-server-pagination';

// The URL-backed hook lives in the app (it depends on react-router); @cia/ui
// stays router-free (only the presentational ServerPaginationFooter lives there).
function wrapper(initial = '/') {
  return ({ children }: { children: React.ReactNode }) =>
    React.createElement(MemoryRouter, { initialEntries: [initial] }, children);
}

function useHarness(config?: Parameters<typeof useServerPagination>[0]) {
  const sp = useServerPagination(config);
  const loc = useLocation();
  return { sp, search: loc.search };
}

describe('useServerPagination', () => {
  it('defaults are omitted from the URL', () => {
    const { result } = renderHook(() => useHarness(), { wrapper: wrapper('/') });
    expect(result.current.sp.page).toBe(0);
    expect(result.current.sp.size).toBe(20);
    act(() => result.current.sp.setPage(0));
    expect(result.current.search).toBe('');
  });

  it('writes non-default page/size to the URL', () => {
    const { result } = renderHook(() => useHarness(), { wrapper: wrapper('/') });
    act(() => result.current.sp.setPage(2));
    expect(result.current.search).toContain('page=2');
    act(() => result.current.sp.setSize(50));
    expect(result.current.search).toContain('size=50');
    expect(result.current.search).not.toContain('page='); // size change reset page to 0 (omitted)
  });

  it('setFilter resets page to 0 and reads back as a filter', () => {
    const { result } = renderHook(() => useHarness(), { wrapper: wrapper('/?page=3') });
    act(() => result.current.sp.setFilter('status', 'ACTIVE'));
    expect(result.current.sp.filters.status).toBe('ACTIVE');
    expect(result.current.search).toContain('status=ACTIVE');
    expect(result.current.search).not.toContain('page=');
  });

  it('toQueryString includes page, size, sort, filters', () => {
    const { result } = renderHook(() => useHarness({ defaultSort: 'createdAt,desc' }), { wrapper: wrapper('/?status=POSTED') });
    const qs = result.current.sp.toQueryString();
    expect(qs).toContain('page=0');
    expect(qs).toContain('size=20');
    expect(qs).toContain('sort=createdAt%2Cdesc');
    expect(qs).toContain('status=POSTED');
  });
});
