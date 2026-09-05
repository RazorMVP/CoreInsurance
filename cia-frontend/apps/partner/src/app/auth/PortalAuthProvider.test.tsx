import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import { PortalAuthProvider } from './PortalAuthProvider';

// This file's `globals: false` vitest config means Testing Library's automatic
// afterEach(cleanup) registration (which detects a global `afterEach`) never
// fires, so two `it` blocks in one file would otherwise leave a stale DOM tree
// from the previous render mounted alongside the next one.
afterEach(cleanup);

function renderWithProviders() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <PortalAuthProvider><div>secured content</div></PortalAuthProvider>
    </QueryClientProvider>,
  );
}

describe('PortalAuthProvider', () => {
  beforeEach(() => configurePortalClient({ baseURL: '', demoMode: true }));
  it('renders children with a mock session in demo mode', async () => {
    renderWithProviders();
    await waitFor(() => expect(screen.getByText('secured content')).toBeInTheDocument());
  });
});

// C1 regression — real (non-demo) mode with no valid session. `portal.ts`'s response
// interceptor dispatches `portal:unauthorized` on every 401; the pre-fix listener called
// `refetch()` unconditionally, so a logged-out visitor loops `getMe()` forever. `vi.hoisted`
// is required here (not a plain top-level const) because `vi.mock('axios', ...)` is hoisted
// above the static `@cia/api-client` / `PortalAuthProvider` imports above — a bare `const`
// declared after those imports would still be in the temporal dead zone when the mock
// factory first runs during module resolution.
const { instance } = vi.hoisted(() => {
  const instance = {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
    request: vi.fn(),
    interceptors: {
      request: { use: () => {} },
      response: { use: () => {} },
    },
  };
  return { instance };
});
vi.mock('axios', () => ({ default: { create: vi.fn(() => instance) } }));

describe('PortalAuthProvider — C1 regression (real-mode 401 loop)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    configurePortalClient({ baseURL: 'https://api.example', demoMode: false });
  });

  it('does not refetch on a stale portal:unauthorized once the session is already known-invalid', async () => {
    instance.get.mockRejectedValue({ response: { status: 401 } });

    renderWithProviders();

    // Session query fails (401) — the provider falls back to the login screen.
    await waitFor(() => expect(screen.getByRole('link', { name: /sign in/i })).toBeInTheDocument());
    const callsAfterInitialFailure = instance.get.mock.calls.length;
    expect(callsAfterInitialFailure).toBeGreaterThan(0);

    // A stray 401 event (e.g. the same failed request's interceptor firing again) must be a
    // no-op now that the session is already known-invalid — asserting no additional GET
    // /portal/auth/me fires. Against the pre-fix code, this dispatch calls `refetch()`
    // unconditionally and the assertion below fails.
    window.dispatchEvent(new CustomEvent('portal:unauthorized'));
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(instance.get.mock.calls.length).toBe(callsAfterInitialFailure);
  });
});
