import {
  Badge, Button, Separator,
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
} from '@cia/ui';

type AuditAction = 'CREATE' | 'UPDATE' | 'DELETE' | 'APPROVE' | 'REJECT' | 'SEND' | 'EXPORT' | 'LOGIN' | 'LOGOUT';
type EntityType  = 'POLICY' | 'CLAIM' | 'CUSTOMER' | 'ENDORSEMENT' | 'QUOTE' | 'RECEIPT' | 'PAYMENT' | 'USER' | 'REINSURANCE' | 'PARTNER_APP';

export interface AuditLogEntry {
  id:         string;
  entityType: EntityType;
  entityId:   string;
  entityRef:  string;
  action:     AuditAction;
  userId:     string;
  userName:   string;
  timestamp:  string;
  ipAddress:  string;
  sessionId:  string;
  oldValue:   Record<string, unknown> | null;
  newValue:   Record<string, unknown> | null;
}

const ACTION_VARIANT: Record<AuditAction, 'active'|'pending'|'rejected'|'draft'|'cancelled'> = {
  CREATE:  'active',
  UPDATE:  'pending',
  DELETE:  'rejected',
  APPROVE: 'active',
  REJECT:  'rejected',
  SEND:    'draft',
  EXPORT:  'draft',
  LOGIN:   'active',
  LOGOUT:  'draft',
};

const ACTION_LABEL: Record<AuditAction, string> = {
  CREATE: 'Created', UPDATE: 'Updated', DELETE: 'Deleted',
  APPROVE: 'Approved', REJECT: 'Rejected', SEND: 'Sent',
  EXPORT: 'Exported', LOGIN: 'Login', LOGOUT: 'Logout',
};

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  entry:        AuditLogEntry | null;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-32 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground break-all">{value}</p>
    </div>
  );
}

function JsonPanel({ label, value }: { label: string; value: Record<string, unknown> | null }) {
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

  const json = JSON.stringify(value, null, 2);
  return (
    <div className="flex-1 min-w-0">
      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">{label}</p>
      <pre className="rounded-lg bg-muted/40 p-3 text-[11px] font-mono text-foreground overflow-x-auto max-h-64 overflow-y-auto whitespace-pre-wrap break-all">
        {json}
      </pre>
    </div>
  );
}

export default function AuditEventDetailSheet({ open, onOpenChange, entry }: Props) {
  if (!entry) return null;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-2xl overflow-y-auto">
        <SheetHeader>
          <div className="flex items-center gap-2">
            <SheetTitle>{entry.entityType} — {entry.entityRef}</SheetTitle>
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
              <DetailRow label="Entity ID"   value={entry.entityId} />
              <DetailRow label="Reference"   value={entry.entityRef} />
              <DetailRow label="Action"      value={ACTION_LABEL[entry.action]} />
              <DetailRow label="Performed By" value={entry.userName} />
              <DetailRow label="Timestamp"   value={entry.timestamp} />
              <DetailRow label="IP Address"  value={entry.ipAddress} />
              <DetailRow label="Session ID"  value={entry.sessionId} />
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
