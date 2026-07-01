import { useState } from 'react';
import {
  Badge,
  PageHeader,
  PageSection,
  Skeleton,
} from '@cia/ui';
import {
  type NotificationTemplateDto,
  type NotificationTemplateType,
  type NotificationChannel,
} from '@cia/api-client';
import { useNotificationTemplates } from '../../hooks/useNotificationTemplates';
import NotificationTemplateEditorSheet from './NotificationTemplateEditorSheet';

// ── Static combo grid ─────────────────────────────────────────────────────────

interface ComboRow {
  templateType: NotificationTemplateType;
  channel: NotificationChannel;
  templateLabel: string;
  channelLabel: string;
}

const COMBOS: ComboRow[] = [
  { templateType: 'RECEIPT',         channel: 'EMAIL', templateLabel: 'Receipt',         channelLabel: 'Email' },
  { templateType: 'RECEIPT',         channel: 'SMS',   templateLabel: 'Receipt',         channelLabel: 'SMS'   },
  { templateType: 'PAYMENT_VOUCHER', channel: 'EMAIL', templateLabel: 'Payment Voucher', channelLabel: 'Email' },
  { templateType: 'PAYMENT_VOUCHER', channel: 'SMS',   templateLabel: 'Payment Voucher', channelLabel: 'SMS'   },
];

interface EditingState {
  templateType: NotificationTemplateType;
  channel: NotificationChannel;
  existingOverride: NotificationTemplateDto | null;
}

function findOverride(
  overrides: NotificationTemplateDto[],
  templateType: NotificationTemplateType,
  channel: NotificationChannel,
): NotificationTemplateDto | null {
  return overrides.find((o) => o.templateType === templateType && o.channel === channel) ?? null;
}

function formatDate(iso: string | null | undefined): string {
  if (iso == null) return '—';
  return new Date(iso).toLocaleString('en-GB', {
    day: '2-digit', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

// ── Page ─────────────────────────────────────────────────────────────────────

export default function NotificationTemplatesPage() {
  const templatesQuery = useNotificationTemplates();
  const overrides = templatesQuery.data ?? [];

  // The editor sheet (Task 12.3) plugs into this state. For now a row click
  // captures the selected (type, channel) + matched override; 12.3 will render
  // <NotificationTemplateEditorSheet ... /> driven by `editing`.
  const [editing, setEditing] = useState<EditingState | null>(null);

  function openEditor(combo: ComboRow) {
    setEditing({
      templateType: combo.templateType,
      channel: combo.channel,
      existingOverride: findOverride(overrides, combo.templateType, combo.channel),
    });
  }

  const isLoading = templatesQuery.isLoading;

  return (
    <>
      <PageHeader
        title="Notification Templates"
        description="Override the default email and SMS copy sent to customers and beneficiaries after receipts and payments."
      />

      <PageSection>
        <div className="rounded-md border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-muted/40">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">Template</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">Channel</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">State</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">Last edited</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                COMBOS.map((combo) => (
                  <tr key={`${combo.templateType}-${combo.channel}`} className="border-b last:border-0">
                    <td className="px-4 py-3"><Skeleton className="h-4 w-32" /></td>
                    <td className="px-4 py-3"><Skeleton className="h-4 w-16" /></td>
                    <td className="px-4 py-3"><Skeleton className="h-5 w-24 rounded-full" /></td>
                    <td className="px-4 py-3"><Skeleton className="h-4 w-40" /></td>
                  </tr>
                ))
              ) : (
                COMBOS.map((combo) => {
                  const override = findOverride(overrides, combo.templateType, combo.channel);
                  const isOverridden = override !== null;
                  return (
                    <tr
                      key={`${combo.templateType}-${combo.channel}`}
                      className="border-b last:border-0 cursor-pointer transition-colors hover:bg-muted/30"
                      onClick={() => openEditor(combo)}
                    >
                      <td className="px-4 py-3 font-medium">{combo.templateLabel}</td>
                      <td className="px-4 py-3 text-muted-foreground">{combo.channelLabel}</td>
                      <td className="px-4 py-3">
                        {isOverridden ? (
                          <Badge variant="default">Overridden</Badge>
                        ) : (
                          <Badge variant="outline" className="text-muted-foreground">Default</Badge>
                        )}
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">
                        {override ? formatDate(override.updatedAt) : '—'}
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </PageSection>

      {editing && (
        <NotificationTemplateEditorSheet
          open={!!editing}
          onOpenChange={(v) => { if (!v) setEditing(null); }}
          templateType={editing.templateType}
          channel={editing.channel}
          existingOverride={editing.existingOverride}
        />
      )}
    </>
  );
}
