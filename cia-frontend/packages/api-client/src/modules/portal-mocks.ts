import type {
  PortalMeDto, PortalAppSummaryDto, PortalCredentialsDto, PortalRotateSecretDto,
  PortalWebhookDto, PortalUsageDto, TryItResult, RegisterWebhookInput,
} from './portal';

const ME: PortalMeDto = {
  partnerUserId: 'dev-user-1',
  email: 'dev@insurtech.example',
  csrfToken: 'demo-csrf',
  apps: [
    { id: 'grant-1', partnerAppId: 'app-1', partnerUserId: 'dev-user-1', email: 'dev@insurtech.example', role: 'MANAGER', createdAt: '2026-08-01T09:00:00Z' },
    { id: 'grant-2', partnerAppId: 'app-2', partnerUserId: 'dev-user-1', email: 'dev@insurtech.example', role: 'VIEWER',  createdAt: '2026-08-02T09:00:00Z' },
  ],
};
const APPS: PortalAppSummaryDto[] = [
  { partnerAppId: 'app-1', clientId: 'insurtech-aggregator', tenantSchema: 'tenant_acme', tenantLabel: 'Acme Insurance', scopes: ['products:read', 'quotes:create', 'policies:read', 'webhooks:manage'], rateTier: 'GROWTH',  status: 'ACTIVE', role: 'MANAGER' },
  { partnerAppId: 'app-2', clientId: 'embedded-checkout',   tenantSchema: 'tenant_leadway', tenantLabel: 'Leadway',      scopes: ['products:read', 'policies:read'], rateTier: 'STARTER', status: 'ACTIVE', role: 'VIEWER' },
];
const CREDS: PortalCredentialsDto = { clientId: 'insurtech-aggregator', scopes: APPS[0].scopes };
let WEBHOOKS: PortalWebhookDto[] = [
  { id: 'wh-1', targetUrl: 'https://insurtech.example/hooks/cia', eventTypes: ['policy.bound', 'claim.approved'], active: true, createdAt: '2026-08-10T10:00:00Z', updatedAt: '2026-08-10T10:00:00Z' },
];
const USAGE: PortalUsageDto = {
  today: { total: 412, success: 388, clientError: 20, serverError: 4 },
  history: Array.from({ length: 14 }, (_, i) => {
    const d = new Date(Date.UTC(2026, 7, 20 - i));
    const total = 300 + ((i * 37) % 220);
    const clientError = (i * 5) % 18;
    const serverError = i % 4;
    return { date: d.toISOString().slice(0, 10), total, success: total - clientError - serverError, clientError, serverError };
  }),
  webhookDeliveries: { registrations: 1, activeRegistrations: 1, totalDeliveries: 240, successfulDeliveries: 231, failedDeliveries: 9, lastDeliveryAt: '2026-08-20T14:03:00Z' },
  errorRate: (20 + 4) / 412,
};

const delay = <T>(v: T) => new Promise<T>((r) => setTimeout(() => r(v), 120));

export const mockPortalApi = {
  __me: () => ME,
  __creds: () => CREDS,
  __rotate: (): PortalRotateSecretDto => ({ clientId: 'insurtech-aggregator', clientSecret: 'demo-secret-' + Math.random().toString(36).slice(2, 14) }),
  getMe: () => delay(ME),
  getApps: () => delay(APPS),
  getCredentials: (_appId: string) => delay(CREDS),
  rotateSecret: (_appId: string) => delay(mockPortalApi.__rotate()),
  getWebhooks: (_appId: string) => delay(WEBHOOKS),
  createWebhook: (_appId: string, input: RegisterWebhookInput) => {
    const wh: PortalWebhookDto = { id: 'wh-' + Math.random().toString(36).slice(2, 8), targetUrl: input.targetUrl, eventTypes: input.eventTypes, active: true, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
    WEBHOOKS = [wh, ...WEBHOOKS];
    return delay(wh);
  },
  deleteWebhook: (_appId: string, id: string) => { WEBHOOKS = WEBHOOKS.filter((w) => w.id !== id); return delay(undefined); },
  getUsage: (_appId: string) => delay(USAGE),
  logout: () => delay({ logoutUrl: '/' }),
  tryIt: (_appId: string, method: string, path: string): Promise<TryItResult> =>
    delay(method === 'GET' && path.replace(/^\/+/, '').startsWith('products')
      ? { status: 200, body: { data: [{ id: 'prod-1', name: 'Motor Comprehensive' }], meta: { total: 1 } } }
      : { status: 403, body: { errors: [{ code: 'INSUFFICIENT_SCOPE', message: 'Token is missing scope: quotes:create' }] } }),
};
