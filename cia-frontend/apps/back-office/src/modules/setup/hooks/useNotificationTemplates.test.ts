import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import {
  useNotificationTemplates,
  useSaveNotificationTemplate,
} from './useNotificationTemplates';

vi.mock('@cia/api-client', () => ({
  listNotificationTemplates: vi.fn(),
  createNotificationTemplate: vi.fn(),
  updateNotificationTemplate: vi.fn(),
  deleteNotificationTemplate: vi.fn(),
  getNotificationTemplateDefaults: vi.fn(),
  getNotificationTemplateVariables: vi.fn(),
  previewNotificationTemplate: vi.fn(),
}));

vi.mock('@cia/ui', () => ({
  toast: vi.fn(),
}));

// eslint-disable-next-line import/first
import {
  listNotificationTemplates,
  createNotificationTemplate,
  updateNotificationTemplate,
} from '@cia/api-client';

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return React.createElement(
    QueryClientProvider,
    { client: queryClient },
    children,
  );
}

describe('useNotificationTemplates', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns the list via the mocked listNotificationTemplates fetcher', async () => {
    // allow-mock: Vitest fixture for hook test
    const mockTemplates = [
      {
        id: 'tpl-1',
        templateType: 'RECEIPT' as const,
        channel: 'EMAIL' as const,
        subjectTemplate: 'Receipt {{receiptNumber}}',
        bodyTemplate: '<p>Thanks</p>',
      },
    ];
    (listNotificationTemplates as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      mockTemplates,
    );

    const { result } = renderHook(() => useNotificationTemplates(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].id).toBe('tpl-1');
    expect(listNotificationTemplates).toHaveBeenCalledTimes(1);
  });
});

describe('useSaveNotificationTemplate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('calls createNotificationTemplate when no id is supplied', async () => {
    (createNotificationTemplate as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      id: 'new-1',
    });

    const { result } = renderHook(() => useSaveNotificationTemplate(), { wrapper });

    const req = {
      templateType: 'RECEIPT' as const,
      channel: 'EMAIL' as const,
      subjectTemplate: 'Hi',
      bodyTemplate: '<p>Body</p>',
    };
    result.current.mutate({ req });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(createNotificationTemplate).toHaveBeenCalledWith(req);
    expect(updateNotificationTemplate).not.toHaveBeenCalled();
  });

  it('calls updateNotificationTemplate when an id is present', async () => {
    (updateNotificationTemplate as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      id: 'tpl-1',
    });

    const { result } = renderHook(() => useSaveNotificationTemplate(), { wrapper });

    const req = {
      templateType: 'PAYMENT_VOUCHER' as const,
      channel: 'SMS' as const,
      subjectTemplate: null,
      bodyTemplate: 'short body',
    };
    result.current.mutate({ id: 'tpl-1', req });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(updateNotificationTemplate).toHaveBeenCalledWith('tpl-1', req);
    expect(createNotificationTemplate).not.toHaveBeenCalled();
  });
});
