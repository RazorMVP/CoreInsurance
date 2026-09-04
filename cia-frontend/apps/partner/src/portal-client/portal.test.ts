import { describe, it, expect } from 'vitest';
import {
  PortalMeDtoSchema, PortalAppSummaryDtoSchema, PortalCredentialsDtoSchema,
  PortalRotateSecretDtoSchema, PortalWebhookDtoSchema, PortalUsageDtoSchema,
  isPortalDemoMode, configurePortalClient,
} from '@cia/api-client';
import { mockPortalApi } from '@cia/api-client';

describe('portal mock adapter is contract-valid', () => {
  it('every mock response parses against its schema', async () => {
    expect(() => PortalMeDtoSchema.parse(mockPortalApi.__me())).not.toThrow();
    (await mockPortalApi.getApps()).forEach((a) => expect(() => PortalAppSummaryDtoSchema.parse(a)).not.toThrow());
    expect(() => PortalCredentialsDtoSchema.parse(mockPortalApi.__creds())).not.toThrow();
    expect(() => PortalRotateSecretDtoSchema.parse(mockPortalApi.__rotate())).not.toThrow();
    (await mockPortalApi.getWebhooks('app-1')).forEach((w) => expect(() => PortalWebhookDtoSchema.parse(w)).not.toThrow());
    const usage = await mockPortalApi.getUsage('app-1');
    expect(() => PortalUsageDtoSchema.parse(usage)).not.toThrow();
  });
});

describe('configurePortalClient', () => {
  it('sets demo mode', () => {
    configurePortalClient({ baseURL: '', demoMode: true });
    expect(isPortalDemoMode()).toBe(true);
    configurePortalClient({ baseURL: 'http://x', demoMode: false });
    expect(isPortalDemoMode()).toBe(false);
  });
});
