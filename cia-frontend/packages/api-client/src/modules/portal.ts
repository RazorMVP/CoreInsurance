/**
 * Partner Portal BFF (`/portal/**`) client + schemas.
 *
 * The BFF is cookie-session (HttpOnly `cia_portal_session`) with a double-submit
 * CSRF token. The shared `apiClient` is a Bearer-token axios singleton with no
 * `withCredentials`, so the portal owns its OWN credentialed axios instance here.
 * Real responses are still validated through the package's `apiEnvelope` zod
 * envelope, so this module honors the "zod-validated" contract while keeping
 * cookie/CSRF concerns off the Bearer client.
 *
 * Wire shapes mirror the BFF Java records exactly (see the Sub-project A spec).
 * File ordering: enums up top (dependency-free), DTOs after.
 */
import axios, { AxiosInstance } from 'axios';
import { z } from 'zod';
import { apiEnvelope } from '../validation';

// ── Enums ──
export const GrantRoleSchema = z.enum(['MANAGER', 'VIEWER']);
export type GrantRole = z.infer<typeof GrantRoleSchema>;

// ── DTOs ──
export const PortalGrantDtoSchema = z.object({
  id: z.string(),
  partnerAppId: z.string(),
  partnerUserId: z.string(),
  email: z.string(),
  role: GrantRoleSchema,
  createdAt: z.string(),
});
export type PortalGrantDto = z.infer<typeof PortalGrantDtoSchema>;

export const PortalMeDtoSchema = z.object({
  partnerUserId: z.string(),
  email: z.string(),
  csrfToken: z.string(),
  apps: z.array(PortalGrantDtoSchema),
});
export type PortalMeDto = z.infer<typeof PortalMeDtoSchema>;

export const PortalLogoutDtoSchema = z.object({ logoutUrl: z.string() });
export type PortalLogoutDto = z.infer<typeof PortalLogoutDtoSchema>;

export const PortalAppSummaryDtoSchema = z.object({
  partnerAppId: z.string(),
  clientId: z.string(),
  tenantSchema: z.string(),
  tenantLabel: z.string(),
  scopes: z.array(z.string()),
  rateTier: z.string(),
  status: z.string(),
  role: GrantRoleSchema,
});
export type PortalAppSummaryDto = z.infer<typeof PortalAppSummaryDtoSchema>;

export const PortalCredentialsDtoSchema = z.object({
  clientId: z.string(),
  scopes: z.array(z.string()),
});
export type PortalCredentialsDto = z.infer<typeof PortalCredentialsDtoSchema>;

export const PortalRotateSecretDtoSchema = z.object({
  clientId: z.string(),
  clientSecret: z.string(),
});
export type PortalRotateSecretDto = z.infer<typeof PortalRotateSecretDtoSchema>;

export const PortalWebhookDtoSchema = z.object({
  id: z.string(),
  targetUrl: z.string(),
  eventTypes: z.array(z.string()),
  active: z.boolean(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type PortalWebhookDto = z.infer<typeof PortalWebhookDtoSchema>;

export interface RegisterWebhookInput {
  targetUrl: string;
  secret: string;      // min length 16 — enforced upstream
  eventTypes: string[];
}

export const UsageDayDtoSchema = z.object({
  total: z.number(),
  success: z.number(),
  clientError: z.number(),
  serverError: z.number(),
});
export type UsageDayDto = z.infer<typeof UsageDayDtoSchema>;

export const UsageHistoryEntryDtoSchema = z.object({
  date: z.string(),
  total: z.number(),
  success: z.number(),
  clientError: z.number(),
  serverError: z.number(),
});
export type UsageHistoryEntryDto = z.infer<typeof UsageHistoryEntryDtoSchema>;

export const WebhookDeliverySummaryDtoSchema = z.object({
  registrations: z.number(),
  activeRegistrations: z.number(),
  totalDeliveries: z.number(),
  successfulDeliveries: z.number(),
  failedDeliveries: z.number(),
  lastDeliveryAt: z.string().nullable(),
});
export type WebhookDeliverySummaryDto = z.infer<typeof WebhookDeliverySummaryDtoSchema>;

export const PortalUsageDtoSchema = z.object({
  today: UsageDayDtoSchema,
  history: z.array(UsageHistoryEntryDtoSchema),
  webhookDeliveries: WebhookDeliverySummaryDtoSchema,
  errorRate: z.number(),
});
export type PortalUsageDto = z.infer<typeof PortalUsageDtoSchema>;

export interface TryItResult {
  status: number;
  body: unknown;
}

// ── Credentialed client ──
//
// The BFF is a same-origin-in-prod, credentialed (cookie-session) API — deliberately
// separate from the shared Bearer-token `apiClient`. `configurePortalClient` is called
// once at app boot (mirrors `initApiClient`); `setPortalCsrfToken` is called after every
// `/portal/auth/me` / login so subsequent mutations carry the double-submit token.

let portalClient: AxiosInstance = axios.create({ withCredentials: true });
let demoMode = false;
let csrfToken: string | null = null;

export function configurePortalClient(opts: { baseURL: string; demoMode: boolean }) {
  demoMode = opts.demoMode;
  portalClient = axios.create({ baseURL: opts.baseURL, withCredentials: true });
  portalClient.interceptors.request.use((config) => {
    const method = (config.method ?? 'get').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD' && csrfToken) {
      config.headers = config.headers ?? {};
      (config.headers as Record<string, string>)['X-CSRF-Token'] = csrfToken;
    }
    return config;
  });
  portalClient.interceptors.response.use(
    (res) => res,
    (error) => {
      if (error.response?.status === 401) window.dispatchEvent(new CustomEvent('portal:unauthorized'));
      return Promise.reject(error);
    },
  );
}

export function setPortalCsrfToken(token: string | null) { csrfToken = token; }
export function isPortalDemoMode() { return demoMode; }

async function portalGet<T extends z.ZodTypeAny>(url: string, schema: T): Promise<z.infer<T>> {
  const res = await portalClient.get(url);
  const parsed = apiEnvelope(schema).parse(res.data) as { data: z.infer<T> };
  return parsed.data;
}
async function portalList<T extends z.ZodTypeAny>(url: string, item: T): Promise<z.infer<T>[]> {
  const res = await portalClient.get(url);
  return apiEnvelope(z.array(item)).parse(res.data).data;
}
async function portalPost<T extends z.ZodTypeAny>(url: string, body: unknown, schema: T): Promise<z.infer<T>> {
  const res = await portalClient.post(url, body);
  const parsed = apiEnvelope(schema).parse(res.data) as { data: z.infer<T> };
  return parsed.data;
}
async function portalDelete(url: string): Promise<void> {
  await portalClient.delete(url);
}
// Try-it: relay status + body VERBATIM (never through apiEnvelope — 403/429 bodies must survive).
async function portalTry(appId: string, method: string, path: string, body?: unknown): Promise<TryItResult> {
  const clean = path.replace(/^\/+/, '');
  const res = await portalClient.request({
    url: `/portal/apps/${appId}/try/${clean}`,
    method: method.toLowerCase(),
    data: ['GET', 'HEAD'].includes(method.toUpperCase()) ? undefined : body,
    validateStatus: () => true,
  });
  return { status: res.status, body: res.data };
}

// ── Real API — one function per BFF endpoint, mirroring `mockPortalApi`'s shape so a
// later task can branch on `isPortalDemoMode()` between this object and the mock. ──

export function getMe(): Promise<PortalMeDto> {
  return portalGet('/portal/auth/me', PortalMeDtoSchema);
}
export function logout(): Promise<PortalLogoutDto> {
  return portalPost('/portal/auth/logout', undefined, PortalLogoutDtoSchema);
}
export function getApps(): Promise<PortalAppSummaryDto[]> {
  return portalList('/portal/apps', PortalAppSummaryDtoSchema);
}
export function getCredentials(appId: string): Promise<PortalCredentialsDto> {
  return portalGet(`/portal/apps/${appId}/credentials`, PortalCredentialsDtoSchema);
}
export function rotateSecret(appId: string): Promise<PortalRotateSecretDto> {
  return portalPost(`/portal/apps/${appId}/credentials/rotate`, undefined, PortalRotateSecretDtoSchema);
}
export function getWebhooks(appId: string): Promise<PortalWebhookDto[]> {
  return portalList(`/portal/apps/${appId}/webhooks`, PortalWebhookDtoSchema);
}
export function createWebhook(appId: string, input: RegisterWebhookInput): Promise<PortalWebhookDto> {
  return portalPost(`/portal/apps/${appId}/webhooks`, input, PortalWebhookDtoSchema);
}
export function deleteWebhook(appId: string, webhookId: string): Promise<void> {
  return portalDelete(`/portal/apps/${appId}/webhooks/${webhookId}`);
}
export function getUsage(appId: string): Promise<PortalUsageDto> {
  return portalGet(`/portal/apps/${appId}/usage`, PortalUsageDtoSchema);
}
export function tryIt(appId: string, method: string, path: string, body?: unknown): Promise<TryItResult> {
  return portalTry(appId, method, path, body);
}
