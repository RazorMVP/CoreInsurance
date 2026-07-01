import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  PageSection, Separator, Skeleton, toast,
} from '@cia/ui';
import { type ColumnDef, type Row } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  AuditAlertDtoSchema, pageSchema,
  type ApiError, type ApiResponse,
  type AlertType, type AuditAlertDto,
} from '@cia/api-client';
import AlertConfigDialog from './AlertConfigDialog';
import { formatTimestamp } from '@/lib/format';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

// allow-mock: fallback while /audit/alerts is in flight
const mockAlerts: AuditAlertDto[] = [
  {
    id: 'alt1', alertType: 'FAILED_LOGIN', severity: 'HIGH',
    description: '3 consecutive failed login attempts from IP 154.113.22.9 for user emeka.eze@nubeero.com.',
    triggeredAt: '2026-04-24T08:23:09Z', acknowledged: false,
    userName: 'Emeka Eze',
  },
  {
    id: 'alt2', alertType: 'LARGE_FINANCIAL_APPROVAL', severity: 'HIGH',
    description: 'Claim DV PAY-2026-00002 approved for ₦225,000,000 — exceeds the ₦50M threshold.',
    triggeredAt: '2026-03-18T16:50:00Z', acknowledged: false,
    userName: 'PAY-2026-00002',
  },
  {
    id: 'alt3', alertType: 'OFF_HOURS_ACTIVITY', severity: 'MEDIUM',
    description: 'User chukwudi.obi@nubeero.com logged in at 21:44 (outside business hours 09:00–17:00).',
    triggeredAt: '2026-04-22T21:44:10Z', acknowledged: false,
    userName: 'Chukwudi Obi',
  },
  {
    id: 'alt4', alertType: 'FAILED_LOGIN', severity: 'MEDIUM',
    description: '2 failed login attempts from a new IP (105.112.88.1) for user ngozi.adeyemi@nubeero.com.',
    triggeredAt: '2026-04-21T14:11:00Z', acknowledged: true,
    acknowledgedAt: '2026-04-21T14:30:00Z', acknowledgedBy: 'Akinwale Nubeero',
    userName: 'Ngozi Adeyemi',
  },
  {
    id: 'alt5', alertType: 'BULK_DELETE', severity: 'CRITICAL',
    description: '7 customer records deleted within 5 minutes by user adaeze@nubeero.com.',
    triggeredAt: '2026-04-20T11:05:00Z', acknowledged: true,
    acknowledgedAt: '2026-04-20T11:15:00Z', acknowledgedBy: 'Akinwale Nubeero',
    userName: 'Adaeze Nwosu',
  },
];

const ALERT_TYPE_LABEL: Record<AlertType, string> = {
  FAILED_LOGIN:             'Failed Logins',
  BULK_DELETE:              'Bulk Delete',
  OFF_HOURS_ACTIVITY:       'Off-Hours Activity',
  LARGE_FINANCIAL_APPROVAL: 'Large Approval',
};

// Backend severity is a free-form string; the lookup falls back to 'draft'
// for anything unrecognised so a new severity value doesn't crash rendering.
const SEVERITY_VARIANT: Record<string, 'active' | 'pending' | 'rejected' | 'draft'> = {
  LOW:      'draft',
  MEDIUM:   'pending',
  HIGH:     'rejected',
  CRITICAL: 'rejected',
};

export default function AlertsTab() {
  const queryClient = useQueryClient();
  const alertsQuery = useQuery<AuditAlertDto[]>({
    queryKey: ['audit', 'alerts'],
    queryFn: async () => {
      // Backend serves Page<AuditAlertResponse>; unwrap content[].
      const res = await apiClient.get('/api/v1/audit/alerts');
      const page = pageSchema(AuditAlertDtoSchema).parse(res.data.data);
      return page.content;
    },
  });
  const alerts = alertsQuery.data ?? mockAlerts;
  const [configOpen,         setConfigOpen]         = useState(false);
  const [acknowledgeTarget,  setAcknowledgeTarget]  = useState<AuditAlertDto | null>(null);
  // "View details" — read-only inspection of the alert. Backend stores
  // additional context in `metadata` as a JSON string; we surface it raw so
  // the on-call engineer can copy/paste into a ticket without losing fidelity.
  const [detailTarget,       setDetailTarget]       = useState<AuditAlertDto | null>(null);

  const acknowledge = useMutation({
    mutationFn: async (id: string) => {
      await apiClient.post(`/api/v1/audit/alerts/${id}/acknowledge`);
    },
    onSuccess: (_, id) => {
      queryClient.invalidateQueries({ queryKey: ['audit', 'alerts'] });
      toast({
        title: 'Alert acknowledged',
        description: `Alert ${id.slice(0, 8)} marked as reviewed.`,
      });
      setAcknowledgeTarget(null);
    },
    onError: (error) => {
      const ax = error as ApiHttpError;
      const errors: ApiError[] = ax?.response?.data?.errors ?? [];
      const description = errors.length > 0
        ? errors.map(e => e.message).filter(Boolean).join('. ')
        : ax?.message ?? 'An unexpected error occurred. Please try again.';
      toast({ variant: 'destructive', title: 'Acknowledge failed', description });
    },
  });

  const openAlerts = alerts.filter(a => !a.acknowledged).length;

  const columns: ColumnDef<AuditAlertDto>[] = [
    {
      accessorKey: 'triggeredAt',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Detected" />,
      cell: ({ getValue }) => (
        <span className="font-mono text-xs text-muted-foreground whitespace-nowrap">
          {formatTimestamp(getValue() as string | null | undefined)}
        </span>
      ),
    },
    {
      accessorKey: 'alertType',
      header: 'Alert Type',
      cell: ({ getValue }) => {
        const t = getValue() as AlertType;
        return <span className="text-sm font-medium text-foreground">{ALERT_TYPE_LABEL[t] ?? t}</span>;
      },
    },
    {
      accessorKey: 'severity',
      header: 'Severity',
      cell: ({ getValue }) => {
        const s = getValue() as string;
        return <Badge variant={SEVERITY_VARIANT[s] ?? 'draft'} className="text-[10px]">{s}</Badge>;
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
      id: 'status',
      header: 'Status',
      accessorFn: (row) => row.acknowledged ? 'ACKNOWLEDGED' : 'OPEN',
      cell: ({ row }) => {
        const ack = row.original.acknowledged;
        return (
          <div>
            <Badge variant={ack ? 'active' : 'draft'} className="text-[10px]">
              {ack ? 'acknowledged' : 'open'}
            </Badge>
            {ack && row.original.acknowledgedBy && (
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
          row={row as Row<AuditAlertDto>}
          actions={[
            ...(!row.original.acknowledged ? [{
              label: 'Acknowledge',
              onClick: (r: Row<AuditAlertDto>) => setAcknowledgeTarget(r.original),
            }] : []),
            { label: 'View details', onClick: (r: Row<AuditAlertDto>) => setDetailTarget(r.original) },
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
          {alertsQuery.isLoading ? (
            <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
          ) : (
            <DataTable
              columns={columns}
              data={alerts}
              toolbar={{ searchColumn: 'description', searchPlaceholder: 'Search alerts…' }}
            />
          )}
        </PageSection>

        <Separator />

        {/* Alert type summary */}
        <PageSection title="Alert Thresholds" description="Currently configured detection rules.">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            {([
              { label: 'Failed Login Trigger', value: '≥ 3 attempts',         type: 'FAILED_LOGIN' as AlertType },
              { label: 'Bulk Delete Trigger',  value: '≥ 5 in 5 minutes',     type: 'BULK_DELETE' as AlertType },
              { label: 'Off-Hours Window',     value: 'Outside 09:00–17:00',   type: 'OFF_HOURS_ACTIVITY' as AlertType },
              { label: 'Large Approval',       value: '≥ ₦50,000,000',         type: 'LARGE_FINANCIAL_APPROVAL' as AlertType },
            ]).map(t => (
              <div key={t.type} className="rounded-lg border p-3 space-y-1">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {ALERT_TYPE_LABEL[t.type]}
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
                  Acknowledge the <span className="font-medium text-foreground">{ALERT_TYPE_LABEL[acknowledgeTarget.alertType] ?? acknowledgeTarget.alertType}</span> alert
                  {acknowledgeTarget.userName && <> for <span className="font-medium text-foreground">{acknowledgeTarget.userName}</span></>}?
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
            <Button variant="outline" onClick={() => setAcknowledgeTarget(null)} disabled={acknowledge.isPending}>
              Cancel
            </Button>
            <Button
              onClick={() => acknowledgeTarget && acknowledge.mutate(acknowledgeTarget.id)}
              disabled={acknowledge.isPending}
            >
              {acknowledge.isPending ? 'Acknowledging…' : 'Acknowledge'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* View details — read-only inspection */}
      <Dialog open={detailTarget !== null} onOpenChange={(v) => { if (!v) setDetailTarget(null); }}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Alert Details</DialogTitle>
            <DialogDescription>
              {detailTarget && (
                <>
                  <span className="font-medium text-foreground">{ALERT_TYPE_LABEL[detailTarget.alertType] ?? detailTarget.alertType}</span>
                  {' · '}
                  <Badge variant={SEVERITY_VARIANT[detailTarget.severity] ?? 'draft'} className="text-[10px]">
                    {detailTarget.severity.toLowerCase()}
                  </Badge>
                </>
              )}
            </DialogDescription>
          </DialogHeader>
          {detailTarget && (
            <div className="space-y-3 text-sm">
              <div className="rounded-lg bg-muted/40 px-4 py-3">
                <p className="text-foreground leading-relaxed">{detailTarget.description}</p>
              </div>
              <div className="grid grid-cols-2 gap-3 text-xs">
                <div>
                  <p className="text-muted-foreground">Triggered at</p>
                  <p className="text-foreground font-mono">{detailTarget.triggeredAt}</p>
                </div>
                {detailTarget.userName && (
                  <div>
                    <p className="text-muted-foreground">User</p>
                    <p className="text-foreground">{detailTarget.userName}</p>
                  </div>
                )}
                {detailTarget.acknowledged && (
                  <>
                    <div>
                      <p className="text-muted-foreground">Acknowledged at</p>
                      <p className="text-foreground font-mono">{detailTarget.acknowledgedAt ?? '—'}</p>
                    </div>
                    <div>
                      <p className="text-muted-foreground">Acknowledged by</p>
                      <p className="text-foreground">{detailTarget.acknowledgedBy ?? '—'}</p>
                    </div>
                  </>
                )}
              </div>
              {detailTarget.metadata && (
                <div>
                  <p className="text-xs text-muted-foreground mb-1">Metadata</p>
                  <pre className="rounded-md bg-muted/40 px-3 py-2 text-xs overflow-x-auto">
                    {detailTarget.metadata}
                  </pre>
                </div>
              )}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setDetailTarget(null)}>Close</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
