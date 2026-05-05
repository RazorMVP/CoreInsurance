import { useMemo, useState } from 'react';
import {
  Badge, Button, Input, Label, PageSection, Separator, Skeleton,
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  apiClient,
  AuditLogDtoSchema, LoginAuditLogDtoSchema, UserActivitySummaryDtoSchema, pageSchema,
  type AuditLogDto, type LoginAuditLogDto, type UserActivitySummaryDto,
} from '@cia/api-client';

// ── Shared helpers ────────────────────────────────────────────────────────────
function Table({ headers, rows }: { headers: string[]; rows: (string | number)[][] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-muted/40">
            {headers.map(h => (
              <th key={h} className="h-9 px-4 text-left text-xs font-semibold text-muted-foreground whitespace-nowrap">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className={i < rows.length - 1 ? 'border-b' : ''}>
              {row.map((cell, j) => (
                <td key={j} className="px-4 py-3 text-sm text-foreground whitespace-nowrap">{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function exportCSV(filename: string, headers: string[], rows: (string | number)[][]) {
  const csv = [headers, ...rows]
    .map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(','))
    .join('\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href = url;
  a.download = `${filename}-${new Date().toISOString().split('T')[0]}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

function ExportButton({ filename, headers, rows, disabled }: {
  filename: string;
  headers:  string[];
  rows:     (string | number)[][];
  disabled?: boolean;
}) {
  return (
    <Button variant="outline" size="sm" onClick={() => exportCSV(filename, headers, rows)} disabled={disabled || rows.length === 0}>
      Export CSV
    </Button>
  );
}

// ── Mock data — kept for the tabs whose backend wiring is deferred ───────────
//
// The 3 tabs below remain on mock data because:
//   • actions-by-user — overlaps with /audit/reports/user-activity (which is
//     already wired in the User Activity tab); also requires a userId param
//     for the per-user-events endpoint, which has no UI picker yet.
//   • actions-by-module — backend has no per-module aggregation endpoint;
//     /audit/reports/actions-by-module returns raw events filtered by
//     entityType, not the count breakdown the table expects.
//   • data-changes — backend requires entityType + entityId query params;
//     the UI has no entity picker yet.
// allow-mock: deferred — see above

const mockActionsByUser = [
  ['1', 'Akinwale Nubeero', '145', '42', '67', '8', '28', 'Today'],
  ['2', 'Adaeze Nwosu',    '98',  '31', '54', '0', '13', 'Yesterday'],
  ['3', 'Emeka Eze',       '64',  '18', '39', '1',  '6', '2 days ago'],
  ['4', 'Ngozi Adeyemi',   '41',  '12', '24', '0',  '5', '3 days ago'],
  ['5', 'Chukwudi Obi',    '27',   '8', '15', '0',  '4', '5 days ago'],
];

// allow-mock: backend has no per-module aggregation endpoint
const mockActionsByModule = [
  ['Policies',     '287', '12', '68', '287'],
  ['Claims',       '234',  '8', '52', '234'],
  ['Customers',    '186',  '5', '41', '186'],
  ['Endorsements', '112',  '3', '28', '112'],
  ['Finance',       '89',  '4', '21',  '89'],
  ['Reinsurance',   '67',  '2', '15',  '67'],
  ['Quotation',     '54',  '1', '12',  '54'],
];

// allow-mock: data-changes endpoint requires entityType+entityId; no entity picker in UI yet
const mockDataChanges = [
  ['POL-2026-00001', 'status',             'PENDING_APPROVAL', 'ACTIVE',      'Akinwale Nubeero', '2026-02-01 10:05'],
  ['CLM-2026-00001', 'status',             'REGISTERED',       'PROCESSING',  'Adaeze Nwosu',     '2026-03-14 15:44'],
  ['CLM-2026-00001', 'reserveAmount',      '0',                '650,000',     'Adaeze Nwosu',     '2026-03-14 15:44'],
  ['CLM-2026-00001', 'status',             'PROCESSING',       'APPROVED',    'Akinwale Nubeero', '2026-03-22 09:07'],
  ['USER:u3',        'accessGroupId',      'ag2',              'ag3',         'Akinwale Nubeero', '2026-02-10 08:47'],
  ['END-2026-00001', 'newSumInsured',      '3,500,000',        '4,000,000',   'Adaeze Nwosu',     '2026-02-15 14:02'],
  ['CUSTOMER:c1',    'kycStatus',          'PENDING',          'VERIFIED',    'Akinwale Nubeero', '2026-01-30 10:00'],
];

// ── Headers ───────────────────────────────────────────────────────────────────
const ACTIONS_BY_USER_HEADERS    = ['Rank', 'User', 'Total', 'Creates', 'Updates', 'Deletes', 'Approvals', 'Last Active'];
const ACTIONS_BY_MODULE_HEADERS  = ['Module', 'Total', 'Today', 'This Week', 'This Month'];
const APPROVAL_TRAIL_HEADERS     = ['Entity', 'Type', 'Action', 'Amount', 'Performed By', 'When'];
const DATA_CHANGES_HEADERS       = ['Entity', 'Field', 'Old Value', 'New Value', 'Changed By', 'Timestamp'];
const LOGIN_SECURITY_HEADERS     = ['User', 'Event', 'Status', 'IP Address', 'Timestamp'];
const USER_ACTIVITY_HEADERS      = ['Rank', 'User', 'Total Actions'];

// ── Date-range default (last 30 days) ────────────────────────────────────────
function isoStart(d: Date) { return new Date(d.toISOString().slice(0, 10) + 'T00:00:00Z').toISOString(); }
function isoEnd(d: Date)   { return new Date(d.toISOString().slice(0, 10) + 'T23:59:59Z').toISOString(); }

function defaultRange() {
  const today = new Date();
  const from  = new Date(today);
  from.setDate(from.getDate() - 30);
  return { from: from.toISOString().slice(0, 10), to: today.toISOString().slice(0, 10) };
}

function fmtTimestamp(iso: string): string {
  return iso.replace('T', ' ').replace(/\.\d+Z?$/, '').replace('Z', '');
}

// ── Component ────────────────────────────────────────────────────────────────
export default function ReportsTab() {
  const [range, setRange] = useState(defaultRange);

  function rangeQueryParams() {
    return { from: isoStart(new Date(range.from)), to: isoEnd(new Date(range.to)) };
  }

  // Approvals report — Page<AuditLogResponse>
  const approvalsQuery = useQuery<AuditLogDto[]>({
    queryKey: ['audit', 'reports', 'approvals', range],
    queryFn: async () => {
      const res = await apiClient.get('/api/v1/audit/reports/approvals', {
        params: { ...rangeQueryParams(), size: 100 },
      });
      const page = pageSchema(AuditLogDtoSchema).parse(res.data.data);
      return page.content;
    },
  });

  // Login security report — Page<LoginAuditLogResponse>
  const loginQuery = useQuery<LoginAuditLogDto[]>({
    queryKey: ['audit', 'reports', 'login-security', range],
    queryFn: async () => {
      const res = await apiClient.get('/api/v1/audit/reports/login-security', {
        params: { ...rangeQueryParams(), size: 100 },
      });
      const page = pageSchema(LoginAuditLogDtoSchema).parse(res.data.data);
      return page.content;
    },
  });

  // User activity report — flat List<UserActivitySummary>
  const userActivityQuery = useQuery<UserActivitySummaryDto[]>({
    queryKey: ['audit', 'reports', 'user-activity', range],
    queryFn: async () => {
      const res = await apiClient.get('/api/v1/audit/reports/user-activity', {
        params: rangeQueryParams(),
      });
      return z.array(UserActivitySummaryDtoSchema).parse(res.data.data);
    },
  });

  // Backend → table-row projections
  const approvalRows = useMemo(() => (approvalsQuery.data ?? []).map(e => [
    e.entityId ?? '—',
    e.entityType,
    e.action,
    e.approvalAmount != null ? `₦${e.approvalAmount.toLocaleString()}` : '—',
    e.userName ?? '—',
    fmtTimestamp(e.timestamp),
  ]), [approvalsQuery.data]);

  const loginRows = useMemo(() => (loginQuery.data ?? []).map(e => [
    e.userName ?? '—',
    e.eventType,
    e.success ? 'Success' : 'Failed',
    e.ipAddress ?? '—',
    fmtTimestamp(e.timestamp),
  ]), [loginQuery.data]);

  const userActivityRows = useMemo(() => (userActivityQuery.data ?? []).map((e, i) => [
    String(i + 1),
    e.userName ?? e.userId ?? '—',
    String(e.actionCount),
  ]), [userActivityQuery.data]);

  const dateFilter = (
    <div className="flex items-end gap-2">
      <div className="space-y-1">
        <Label htmlFor="range-from" className="text-xs">From</Label>
        <Input id="range-from" type="date" value={range.from} onChange={e => setRange(r => ({ ...r, from: e.target.value }))} className="h-8 w-36" />
      </div>
      <div className="space-y-1">
        <Label htmlFor="range-to" className="text-xs">To</Label>
        <Input id="range-to" type="date" value={range.to} onChange={e => setRange(r => ({ ...r, to: e.target.value }))} className="h-8 w-36" />
      </div>
    </div>
  );

  return (
    <Tabs defaultValue="approval-trail">
      <div className="flex flex-wrap items-end justify-between gap-3 mb-3">
        <TabsList className="flex-wrap h-auto gap-1">
          <TabsTrigger value="actions-by-user"   className="text-xs">Actions by User</TabsTrigger>
          <TabsTrigger value="actions-by-module" className="text-xs">Actions by Module</TabsTrigger>
          <TabsTrigger value="approval-trail"    className="text-xs">Approval Trail</TabsTrigger>
          <TabsTrigger value="data-changes"      className="text-xs">Data Changes</TabsTrigger>
          <TabsTrigger value="login-security"    className="text-xs">Login Security</TabsTrigger>
          <TabsTrigger value="user-activity"     className="text-xs">User Activity</TabsTrigger>
        </TabsList>
        {dateFilter}
      </div>

      {/* ── Actions by User (deferred — backend wiring needs a userId picker) ── */}
      <TabsContent value="actions-by-user" className="mt-4">
        <PageSection
          title="Actions by User"
          description="Per-user activity breakdown. Backend wiring deferred — see User Activity tab for the available aggregation."
          actions={<ExportButton filename="audit-actions-by-user" headers={ACTIONS_BY_USER_HEADERS} rows={mockActionsByUser} />}
        >
          <Table headers={ACTIONS_BY_USER_HEADERS} rows={mockActionsByUser} />
        </PageSection>
      </TabsContent>

      {/* ── Actions by Module (deferred — backend has no aggregation endpoint) ── */}
      <TabsContent value="actions-by-module" className="mt-4">
        <PageSection
          title="Actions by Module"
          description="Event volume per module. Backend wiring deferred — no aggregation endpoint exists; backend returns raw events filtered by entityType."
          actions={<ExportButton filename="audit-actions-by-module" headers={ACTIONS_BY_MODULE_HEADERS} rows={mockActionsByModule} />}
        >
          <Table headers={ACTIONS_BY_MODULE_HEADERS} rows={mockActionsByModule} />
        </PageSection>
      </TabsContent>

      {/* ── Approval Trail (wired to /audit/reports/approvals) ────────────────── */}
      <TabsContent value="approval-trail" className="mt-4">
        <PageSection
          title="Approval Audit Trail"
          description="All APPROVE and REJECT events across modules in the selected date range."
          actions={<ExportButton filename="audit-approval-trail" headers={APPROVAL_TRAIL_HEADERS} rows={approvalRows} />}
        >
          {approvalsQuery.isLoading
            ? <div className="space-y-3"><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>
            : approvalsQuery.isError
            ? <p className="text-sm text-destructive">Failed to load approvals.</p>
            : <Table headers={APPROVAL_TRAIL_HEADERS} rows={approvalRows} />}
        </PageSection>
      </TabsContent>

      {/* ── Data Changes (deferred — needs entity picker) ─────────────────────── */}
      <TabsContent value="data-changes" className="mt-4">
        <PageSection
          title="Data Change History"
          description="Backend wiring deferred — endpoint requires entityType + entityId query params; no entity picker yet."
          actions={<ExportButton filename="audit-data-changes" headers={DATA_CHANGES_HEADERS} rows={mockDataChanges} />}
        >
          <Table headers={DATA_CHANGES_HEADERS} rows={mockDataChanges} />
        </PageSection>
      </TabsContent>

      {/* ── Login Security (wired to /audit/reports/login-security) ───────────── */}
      <TabsContent value="login-security" className="mt-4">
        <PageSection
          title="Login Security Report"
          description="Login, logout, and failed authentication events in the selected date range."
          actions={<ExportButton filename="audit-login-security" headers={LOGIN_SECURITY_HEADERS} rows={loginRows} />}
        >
          {loginQuery.isLoading
            ? <div className="space-y-3"><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>
            : loginQuery.isError
            ? <p className="text-sm text-destructive">Failed to load login events.</p>
            : <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b bg-muted/40">
                      {LOGIN_SECURITY_HEADERS.map(h => (
                        <th key={h} className="h-9 px-4 text-left text-xs font-semibold text-muted-foreground whitespace-nowrap">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {(loginQuery.data ?? []).map((e, i, arr) => (
                      <tr key={e.id} className={i < arr.length - 1 ? 'border-b' : ''}>
                        <td className="px-4 py-3 font-medium">{e.userName ?? e.userId ?? '—'}</td>
                        <td className="px-4 py-3 text-muted-foreground">{e.eventType}</td>
                        <td className="px-4 py-3">
                          <Badge variant={e.success ? 'active' : 'rejected'} className="text-[10px]">
                            {e.success ? 'Success' : 'Failed'}
                          </Badge>
                        </td>
                        <td className="px-4 py-3 text-muted-foreground">{e.ipAddress ?? '—'}</td>
                        <td className="px-4 py-3 text-muted-foreground">{fmtTimestamp(e.timestamp)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
          }
        </PageSection>
      </TabsContent>

      {/* ── User Activity (wired to /audit/reports/user-activity) ─────────────── */}
      <TabsContent value="user-activity" className="mt-4">
        <PageSection
          title="User Activity Summary"
          description="Ranked activity summary — total operations per user in the selected date range."
          actions={<ExportButton filename="audit-user-activity" headers={USER_ACTIVITY_HEADERS} rows={userActivityRows} />}
        >
          {userActivityQuery.isLoading
            ? <div className="space-y-3"><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>
            : userActivityQuery.isError
            ? <p className="text-sm text-destructive">Failed to load user activity.</p>
            : <Table headers={USER_ACTIVITY_HEADERS} rows={userActivityRows} />}
          <Separator className="my-4" />
          <p className="text-xs text-muted-foreground">
            Action count includes every audited write (CREATE, UPDATE, DELETE, APPROVE, REJECT, etc.). The previous "Most Common Action" and weighted "Activity Score" columns required client-side aggregation that the backend doesn't yet support.
          </p>
        </PageSection>
      </TabsContent>
    </Tabs>
  );
}
