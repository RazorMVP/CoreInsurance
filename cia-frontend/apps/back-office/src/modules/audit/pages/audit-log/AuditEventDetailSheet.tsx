import {
  Badge, Button, Separator,
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
} from '@cia/ui';
import type { AuditAction, AuditLogDto } from '@cia/api-client';

// Re-export the canonical type as AuditLogEntry so existing callers
// (AuditLogTab) keep working without an import-name change.
export type AuditLogEntry = AuditLogDto;

const ACTION_VARIANT: Record<AuditAction, 'active' | 'pending' | 'rejected' | 'draft' | 'cancelled'> = {
  CREATE:  'active',
  UPDATE:  'pending',
  DELETE:  'rejected',
  APPROVE: 'active',
  REJECT:  'rejected',
  SUBMIT:  'draft',
  SEND:    'draft',
  CANCEL:  'cancelled',
  REVERSE: 'rejected',
  EXECUTE: 'active',
};

const ACTION_LABEL: Record<AuditAction, string> = {
  CREATE:  'Created',
  UPDATE:  'Updated',
  DELETE:  'Deleted',
  APPROVE: 'Approved',
  REJECT:  'Rejected',
  SUBMIT:  'Submitted',
  SEND:    'Sent',
  CANCEL:  'Cancelled',
  REVERSE: 'Reversed',
  EXECUTE: 'Executed',
};

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  entry:        AuditLogDto | null;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-32 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground break-all">{value}</p>
    </div>
  );
}

/**
 * Backend stores oldValue / newValue as JSON-serialised strings.
 * Pretty-print on display; if the string is malformed, show the raw
 * value so we never silently swallow auditable data.
 */
function JsonPanel({ label, value }: { label: string; value: string | null | undefined }) {
  if (!value) {
    return (
      <div className="flex-1 min-w-0">
        <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">{label}</p>
        <div className="rounded-lg bg-muted/40 px-4 py-6 text-center">
          <p className="text-xs text-muted-foreground">No data</p>
        </div>
      </div>
    );
  }

  let pretty = value;
  try {
    const parsed = JSON.parse(value) as unknown;
    pretty = JSON.stringify(parsed, null, 2);
  } catch {
    // Leave value as-is if it isn't valid JSON.
  }

  return (
    <div className="flex-1 min-w-0">
      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">{label}</p>
      <pre className="rounded-lg bg-muted/40 p-3 text-[11px] font-mono text-foreground overflow-x-auto max-h-64 overflow-y-auto whitespace-pre-wrap break-all">
        {pretty}
      </pre>
    </div>
  );
}

export default function AuditEventDetailSheet({ open, onOpenChange, entry }: Props) {
  if (!entry) return null;

  // Backend has no entityRef field — compose a display label from
  // entityType + entityId (truncated). Callers may also pre-resolve a
  // friendlier reference, but this never renders empty.
  const entityRef = entry.entityId ? entry.entityId.slice(0, 8) : '—';

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-2xl overflow-y-auto">
        <SheetHeader>
          <div className="flex items-center gap-2">
            <SheetTitle>{entry.entityType} — {entityRef}</SheetTitle>
            <Badge variant={ACTION_VARIANT[entry.action]} className="text-[10px]">
              {ACTION_LABEL[entry.action]}
            </Badge>
          </div>
          <SheetDescription>
            Audit event recorded on {entry.timestamp}
          </SheetDescription>
        </SheetHeader>

        <div className="mt-6 space-y-6">
          {/* Event metadata */}
          <div className="rounded-lg border overflow-hidden">
            <div className="bg-muted/40 px-4 py-2">
              <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Event Details</p>
            </div>
            <div className="px-4 pb-2">
              <DetailRow label="Event ID"    value={entry.id} />
              <DetailRow label="Entity Type" value={entry.entityType} />
              <DetailRow label="Entity ID"   value={entry.entityId ?? '—'} />
              <DetailRow label="Action"      value={ACTION_LABEL[entry.action]} />
              <DetailRow label="Performed By" value={entry.userName ?? entry.userId ?? '—'} />
              <DetailRow label="Timestamp"   value={entry.timestamp} />
              <DetailRow label="IP Address"  value={entry.ipAddress ?? '—'} />
              <DetailRow label="Session ID"  value={entry.sessionId ?? '—'} />
              {entry.approvalAmount != null && (
                <DetailRow label="Approval Amount" value={`₦${entry.approvalAmount.toLocaleString()}`} />
              )}
            </div>
          </div>

          <Separator />

          {/* Before / After diff */}
          <div>
            <p className="text-sm font-semibold text-foreground mb-3">Data Snapshot</p>
            <div className="flex gap-4">
              <JsonPanel label="Before" value={entry.oldValue} />
              <JsonPanel label="After"  value={entry.newValue} />
            </div>
          </div>
        </div>

        <div className="mt-6 flex justify-end">
          <Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button>
        </div>
      </SheetContent>
    </Sheet>
  );
}
