import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import NotificationTemplateEditorSheet from './NotificationTemplateEditorSheet';

// ── mock @cia/ui ─────────────────────────────────────────────────────────
// Radix portals/animations don't work in jsdom; replace with plain HTML.
// Forward refs on Input/Textarea so the editor's bodyRef resolves.
vi.mock('@cia/ui', () => {
  const passthrough =
    (tag: string) =>
    ({ children, className }: { children?: React.ReactNode; className?: string }) =>
      React.createElement(tag, { className }, children);

  const Textarea = React.forwardRef<
    HTMLTextAreaElement,
    React.TextareaHTMLAttributes<HTMLTextAreaElement>
  >((props, ref) => React.createElement('textarea', { ...props, ref }));
  Textarea.displayName = 'Textarea';

  const Input = React.forwardRef<
    HTMLInputElement,
    React.InputHTMLAttributes<HTMLInputElement>
  >((props, ref) => React.createElement('input', { ...props, ref }));
  Input.displayName = 'Input';

  return {
    Badge: ({ children }: { children?: React.ReactNode }) =>
      React.createElement('span', null, children),
    Button: ({
      children,
      onClick,
      disabled,
    }: {
      children?: React.ReactNode;
      onClick?: () => void;
      disabled?: boolean;
    }) => React.createElement('button', { onClick, disabled }, children),
    ConfirmDeleteDialog: () => null,
    Input,
    Label: ({
      children,
      htmlFor,
    }: {
      children?: React.ReactNode;
      htmlFor?: string;
    }) => React.createElement('label', { htmlFor }, children),
    Separator: passthrough('hr'),
    Sheet: ({ children, open }: { children?: React.ReactNode; open?: boolean }) =>
      open ? React.createElement('div', { 'data-testid': 'sheet' }, children) : null,
    SheetContent: passthrough('div'),
    SheetDescription: passthrough('p'),
    SheetHeader: passthrough('div'),
    SheetTitle: passthrough('h2'),
    Skeleton: passthrough('div'),
    Textarea,
  };
});

// ── mock the hook bundle ────────────────────────────────────────────────
const previewMutate = vi.fn();
const saveMutate = vi.fn();
const resetMutate = vi.fn();

vi.mock('../../hooks/useNotificationTemplates', () => ({
  useNotificationTemplateDefaults: () => ({
    // allow-mock: controlled hook return for component test
    data: {
      defaults: [
        {
          templateType: 'RECEIPT',
          channel: 'EMAIL',
          subjectTemplate: 'Receipt subject',
          bodyTemplate: 'Hello ',
        },
      ],
    },
    isLoading: false,
  }),
  useNotificationTemplateVariables: () => ({
    // allow-mock: controlled hook return for component test
    data: {
      variables: [
        {
          templateType: 'RECEIPT',
          channel: 'EMAIL',
          allowedVariables: ['customerName', 'amount'],
        },
      ],
    },
    isLoading: false,
  }),
  usePreviewNotificationTemplate: () => ({
    mutate: previewMutate,
    data: undefined,
  }),
  useSaveNotificationTemplate: () => ({
    mutate: saveMutate,
    isPending: false,
  }),
  useResetNotificationTemplate: () => ({
    mutate: resetMutate,
    isPending: false,
  }),
}));

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

function renderSheet(channel: 'EMAIL' | 'SMS') {
  return render(
    React.createElement(NotificationTemplateEditorSheet, {
      open: true,
      onOpenChange: () => {},
      templateType: 'RECEIPT' as const,
      channel,
      existingOverride: null,
    }),
    { wrapper },
  );
}

describe('NotificationTemplateEditorSheet', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it('inserts {{variable}} at the body cursor on Insert click', async () => {
    const user = userEvent.setup();
    renderSheet('EMAIL');

    const body = screen.getByLabelText('Body') as HTMLTextAreaElement;
    // Body seeds from the default body "Hello ". Place cursor at the end.
    expect(body.value).toBe('Hello ');
    body.setSelectionRange(body.value.length, body.value.length);

    // Click the [Insert] button for the customerName variable.
    const insertBtn = screen
      .getAllByRole('button')
      .find((b) => b.textContent?.includes('customerName'));
    expect(insertBtn).toBeTruthy();
    await user.click(insertBtn!);

    expect((screen.getByLabelText('Body') as HTMLTextAreaElement).value).toBe(
      'Hello {{customerName}}',
    );
  });

  it('renders a Subject input for EMAIL and hides it for SMS', () => {
    renderSheet('EMAIL');
    expect(screen.getByLabelText('Subject')).toBeInTheDocument();
    cleanup();

    renderSheet('SMS');
    expect(screen.queryByLabelText('Subject')).not.toBeInTheDocument();
  });
});
