import { describe, it, expect, beforeEach, vi } from 'vitest';

// Real (non-demo) path coverage for `portal.ts`. Every OTHER test in this app uses
// `demoMode: true`, so the CSRF interceptor, `withCredentials`, `apiEnvelope` parsing, the
// 401→event path, and `portalTry`'s `validateStatus` are otherwise never exercised.
//
// `axios` is mocked so the credentialed instance `configurePortalClient` builds is fully
// controllable and its interceptor handlers are capturable. The mocked module is picked up via
// a dynamic `await import('@cia/api-client')` AFTER `vi.mock('axios', ...)` registers (rather
// than a static top-of-file import) — a static import of a package that transitively imports
// `axios` gets resolved as part of Vite/Vitest's module-graph linking before this file's own
// top-level statements run, so a static import here would race the mock registration.
const handlers: { req?: (c: any) => any; resErr?: (e: any) => any } = {};
const instance = {
  get: vi.fn(), post: vi.fn(), delete: vi.fn(), request: vi.fn(),
  interceptors: {
    request: { use: (fn: any) => { handlers.req = fn; } },
    response: { use: (_ok: any, err: any) => { handlers.resErr = err; } },
  },
};
// `axios` is not a direct dependency of the partner app's own package.json (only
// `@cia/api-client` declares it), and pnpm's strict node_modules linking means a bare
// `await import('axios')` from this file can't resolve it directly — so the create-mock
// itself is captured here instead of imported back from a real axios module.
const axiosCreate = vi.fn(() => instance);
vi.mock('axios', () => ({ default: { create: axiosCreate } }));

const { configurePortalClient, setPortalCsrfToken, getMe, rotateSecret, createWebhook, deleteWebhook, tryIt } =
  await import('@cia/api-client');

beforeEach(() => {
  vi.clearAllMocks();
  configurePortalClient({ baseURL: 'https://api.example', demoMode: false });
});

describe('portal.ts real (non-demo) path', () => {
  it('1. envelope parsing: getMe unwraps { data } and hits /portal/auth/me', async () => {
    instance.get.mockResolvedValue({ data: { data: { partnerUserId: 'u1', email: 'd@e.f', csrfToken: 't', apps: [] } } });
    const me = await getMe();
    expect(me).toEqual({ partnerUserId: 'u1', email: 'd@e.f', csrfToken: 't', apps: [] });
    expect(instance.get).toHaveBeenCalledWith('/portal/auth/me');
  });

  it('2. CSRF is attached on mutations only, and never when the token is null', () => {
    setPortalCsrfToken('tok');
    const postConfig = handlers.req!({ method: 'post', headers: {} });
    expect(postConfig.headers['X-CSRF-Token']).toBe('tok');

    const getConfig = handlers.req!({ method: 'get', headers: {} });
    expect(getConfig.headers['X-CSRF-Token']).toBeUndefined();

    setPortalCsrfToken(null);
    const postConfigNoToken = handlers.req!({ method: 'post', headers: {} });
    expect(postConfigNoToken.headers['X-CSRF-Token']).toBeUndefined();
  });

  it('3. withCredentials: true is passed to axios.create', () => {
    expect(axiosCreate).toHaveBeenCalledWith(expect.objectContaining({ withCredentials: true }));
  });

  it('4. a 401 dispatches exactly one portal:unauthorized event; a 500 dispatches none', async () => {
    const spy = vi.fn();
    window.addEventListener('portal:unauthorized', spy);
    try {
      await expect(handlers.resErr!({ response: { status: 401 } })).rejects.toBeTruthy();
      expect(spy).toHaveBeenCalledTimes(1);

      await expect(handlers.resErr!({ response: { status: 500 } })).rejects.toBeTruthy();
      expect(spy).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener('portal:unauthorized', spy);
    }
  });

  it('5. tryIt relays status + body verbatim (never through apiEnvelope) with validateStatus set', async () => {
    instance.request.mockResolvedValue({ status: 403, data: { errors: [{ code: 'INSUFFICIENT_SCOPE' }] } });
    const result = await tryIt('app-1', 'POST', 'quotes', {});
    expect(result).toEqual({ status: 403, body: { errors: [{ code: 'INSUFFICIENT_SCOPE' }] } });
    const callArgs = instance.request.mock.calls[0][0];
    expect(callArgs.validateStatus).toBeInstanceOf(Function);
    expect(callArgs.validateStatus()).toBe(true);
  });

  it('6. rotate/webhook paths hit the right URLs', async () => {
    instance.post.mockResolvedValue({ data: { data: { clientId: 'c1', clientSecret: 's1' } } });
    await rotateSecret('app-1');
    expect(instance.post).toHaveBeenCalledWith('/portal/apps/app-1/credentials/rotate', undefined);

    instance.post.mockResolvedValue({ data: { data: { id: 'wh-1', targetUrl: 'https://x', eventTypes: [], active: true, createdAt: '', updatedAt: '' } } });
    await createWebhook('app-1', { targetUrl: 'https://x', secret: '0123456789abcdef', eventTypes: ['policy.bound'] });
    expect(instance.post).toHaveBeenCalledWith('/portal/apps/app-1/webhooks', { targetUrl: 'https://x', secret: '0123456789abcdef', eventTypes: ['policy.bound'] });

    instance.delete.mockResolvedValue({ data: {} });
    await deleteWebhook('app-1', 'wh-1');
    expect(instance.delete).toHaveBeenCalledWith('/portal/apps/app-1/webhooks/wh-1');
  });
});
