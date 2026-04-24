import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  PageSection, Separator,
} from '@cia/ui';
import { type ColumnDef, type Row } from '@tanstack/react-table';
import AlertConfigDialog from './AlertConfigDialog';

type AlertType     = 'FAILED_LOGINS' | 'BULK_DELETE' | 'OFF_HOURS_ACTIVITY' | 'LARGE_FINANCIAL_APPROVAL';
type AlertSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
type AlertStatus   = 'OPEN' | 'ACKNOWLEDGED';

interface AuditAlert {
  id:              string;
  alertType:       AlertType;
  severity:        AlertSeverity;
  description:     string;
  detectedAt:      string;
  status:          AlertStatus;
  acknowledgedAt?: string;
  acknowledgedBy?: string;
  entityRef?:      string;
}

const mockAlerts: AuditAlert[] = [
  {
    id: 'alt1', alertType: 'FAILED_LOGINS', severity: 'HIGH',
    description: '3 consecutive failed login attempts from IP 154.113.22.9 for user emeka.eze@nubeero.com.',
    detectedAt: '2026-04-24T08:23:09Z', status: 'OPEN', entityRef: 'Emeka Eze',
  },
  {
    id: 'alt2', alertType: 'LARGE_FINANCIAL_APPROVAL', severity: 'HIGH',
    description: 'Claim DV PAY-2026-00002 approved for ₦225,000,000 — exceeds the ₦50M threshold.',
    detectedAt: '2026-03-18T16:50:00Z', status: 'OPEN', entityRef: 'PAY-2026-00002',
  },
  {
    id: 'alt3', alertType: 'OFF_HOURS_ACTIVITY', severity: 'MEDIUM',
    description: 'User chukwudi.obi@nubeero.com logged in at 21:44 (outside business hours 09:00–17:00).',
    detectedAt: '2026-04-22T21:44:10Z', status: 'OPEN', entityRef: 'Chukwudi Obi',
  },
  {
    id: 'alt4', alertType: 'FAILED_LOGINS', severity: 'MEDIUM',
    description: '2 failed login attempts from a new IP (105.112.88.1) for user ngozi.adeyemi@nubeero.com.',
    detectedAt: '2026-04-21T14:11:00Z', status: 'ACKNOWLEDGED',
    acknowledgedAt: '2026-04-21T14:30:00Z', acknowledgedBy: 'Akinwale Nubeero',
    entityRef: 'Ngozi Adeyemi',
  },
  {
    id: 'alt5', alertType: 'BULK_DELETE', severity: 'CRITICAL',
    description: '7 customer records deleted within 5 minutes by user adaeze@nubeero.com.',
    detectedAt: '2026-04-20T11:05:00Z', status: 'ACKNOWLEDGED',
    acknowledgedAt: '2026-04-20T11:15:00Z', acknowledgedBy: 'Akinwale Nubeero',
    entityRef: 'Adaeze Nwosu',
  },
];

const ALERT_TYPE_LABEL: Record<AlertType, string> = {
  FAILED_LOGINS:            'Failed Logins',
  BULK_DELETE:              'Bulk Delete',
  OFF_HOURS_ACTIVITY:       'Off-Hours Activity',
  LARGE_FINANCIAL_APPROVAL: 'Large Approval',
};

const SEVERITY_VARIANT: Record<AlertSeverity, 'active'|'pending'|'rejected'|'draft'> = {
  LOW:      'draft',
  MEDIUM:   'pending',
  HIGH:     'rejected',
  CRITICAL: 'rejected',
};

const STATUS_VARIANT: Record<AlertStatus, 'active'|'draft'> = {
  OPEN:         'draft',
  ACKNOWLEDGED: 'active',
};

export default function AlertsTab() {
  const [configOpen,         setConfigOpen]         = useState(false);
  const [acknowledgeTarget,  setAcknowledgeTarget]  = useState<AuditAlert | null>(null);

  const openAlerts = mockAlerts.filter(a => a.status === 'OPEN').length;

  const columns: ColumnDef<AuditAlert>[] = [
    {
      accessorKey: 'detectedAt',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Detected" />,
      cell: ({ getValue }) => (
        <span className="font-mono text-xs text-muted-foreground whitespace-nowrap">
          {(getValue() as string).replace('T', ' ').replace('Z', '')}
        </span>
      ),
    },
    {
      accessorKey: 'alertType',
      header: 'Alert Type',
      cell: ({ getValue }) => (
        <span className="text-sm font-medium text-foreground">{ALERT_TYPE_LABEL[getValue() as AlertType]}</span>
      ),
    },
    {
      accessorKey: 'severity',
      header: 'Severity',
      cell: ({ getValue }) => {
        const s = getValue() as AlertSeverity;
        return <Badge variant={SEVERITY_VARIANT[s]} className="text-[10px]">{s}</Badge>;
      },
    },
    {
      accessorKey: 'description',
      header: 'Description',
      cell: ({ getValue }) => (
        <p className="text-sm text-muted-foreground max-w-md line-clamp-2">{getValue() as string}</p>
      ),
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ row }) => {
        const s = row.original.status;
        return (
          <div>
            <Badge variant={STATUS_VARIANT[s]} className="text-[10px]">{s.toLowerCase()}</Badge>
            {s === 'ACKNOWLEDGED' && row.original.acknowledgedBy && (
              <p className="text-xs text-muted-foreground mt-0.5">by {row.original.acknowledgedBy}</p>
            )}
          </div>
        );
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row as Row<AuditAlert>}
          actions={[
            ...(row.original.status === 'OPEN' ? [{
              label: 'Acknowledge',
              onClick: (r: Row<AuditAlert>) => setAcknowledgeTarget(r.original),
            }] : []),
            { label: 'View details', onClick: () => {} },
          ]}
        />
      ),
    },
  ];

  return (
    <>
      <div className="space-y-6">
        {openAlerts > 0 && (
          <div className="flex items-center gap-3 rounded-lg border bg-[var(--status-rejected-bg)] px-4 py-3">
            <Badge variant="rejected" className="text-[10px] shrink-0">{openAlerts}</Badge>
            <p className="text-sm text-foreground">
              open alert{openAlerts !== 1 ? 's' : ''} requiring acknowledgement
            </p>
          </div>
        )}

        <PageSection
          title="Real-Time Alerts"
          description="Automated alerts triggered by suspicious or notable system activity."
          actions={
            <Button variant="outline" size="sm" onClick={() => setConfigOpen(true)}>
              Configure Alerts
            </Button>
          }
        >
          <DataTable
            columns={columns}
            data={mockAlerts}
            toolbar={{ searchColumn: 'description', searchPlaceholder: 'Search alerts…' }}
          />
        </PageSection>

        <Separator />

        {/* Alert type summary */}
        <PageSection title="Alert Thresholds" description="Currently configured detection rules.">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            {[
              { label: 'Failed Login Trigger', value: '≥ 3 attempts',      type: 'FAILED_LOGINS' },
              { label: 'Bulk Delete Trigger',  value: '≥ 5 in 5 minutes',  type: 'BULK_DELETE' },
              { label: 'Off-Hours Window',     value: 'Outside 09:00–17:00', type: 'OFF_HOURS_ACTIVITY' },
              { label: 'Large Approval',       value: '≥ ₦50,000,000',     type: 'LARGE_FINANCIAL_APPROVAL' },
            ].map(t => (
              <div key={t.type} className="rounded-lg border p-3 space-y-1">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {ALERT_TYPE_LABEL[t.type as AlertType]}
                </p>
                <p className="text-sm font-medium text-foreground">{t.value}</p>
                <p className="text-xs text-muted-foreground">{t.label}</p>
              </div>
            ))}
          </div>
          <p className="text-xs text-muted-foreground mt-3">
            System Admin only. Click <button className="text-primary underline underline-offset-2" onClick={() => setConfigOpen(true)}>Configure Alerts</button> to adjust thresholds, business hours, and email recipients.
          </p>
        </PageSection>
      </div>

      <AlertConfigDialog open={configOpen} onOpenChange={setConfigOpen} />

      {/* Acknowledge confirmation */}
      <Dialog open={acknowledgeTarget !== null} onOpenChange={(v) => { if (!v) setAcknowledgeTarget(null); }}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>Acknowledge Alert</DialogTitle>
            <DialogDescription>
              {acknowledgeTarget && (
                <>
                  Acknowledge the <span className="font-medium text-foreground">{ALERT_TYPE_LABEL[acknowledgeTarget.alertType]}</span> alert
                  {acknowledgeTarget.entityRef && <> for <span className="font-medium text-foreground">{acknowledgeTarget.entityRef}</span></>}?
                  This marks the alert as reviewed.
                </>
              )}
            </DialogDescription>
          </DialogHeader>
          {acknowledgeTarget && (
            <div className="rounded-lg bg-muted/40 px-4 py-3">
              <p className="text-xs text-muted-foreground leading-relaxed">{acknowledgeTarget.description}</p>
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setAcknowledgeTarget(null)}>Cancel</Button>
            <Button onClick={() => {
              // TODO: PATCH /api/v1/audit/alerts/{id}/acknowledge
              setAcknowledgeTarget(null);
            }}>
              Acknowledge
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
