import { useMemo, useState } from 'react';
import {
  Badge, Button, Input, Label, PageSection, Separator, Skeleton,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  validatedGet,
  AuditLogDtoSchema, LoginAuditLogDtoSchema, UserActivitySummaryDtoSchema,
  type AuditLogDto, type LoginAuditLogDto, type UserActivitySummaryDto,
} from '@cia/api-client';

const MODULES = ['POLICY', 'CLAIM', 'CUSTOMER', 'ENDORSEMENT', 'QUOTE', 'RECEIPT', 'PAYMENT', 'USER', 'REINSURANCE', 'PARTNER_APP'] as const;

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

// ── Headers ───────────────────────────────────────────────────────────────────
//
// Backend events flow as raw AuditLogResponse for the per-user, per-module,
// and data-changes endpoints. We project them to the existing column shapes
// where possible and drop columns the backend can't supply (e.g. count
// breakdowns from the previous mock-only views).

const ACTIONS_BY_USER_HEADERS    = ['Timestamp', 'Entity', 'Action', 'IP Address'];
const ACTIONS_BY_MODULE_HEADERS  = ['Timestamp', 'Entity ID', 'Action', 'User'];
const APPROVAL_TRAIL_HEADERS     = ['Entity', 'Type', 'Action', 'Amount', 'Performed By', 'When'];
const DATA_CHANGES_HEADERS       = ['Field/Action', 'Old Value', 'New Value', 'Changed By', 'Timestamp'];
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

  // Tab-specific filter state. The 3 tabs that need extra inputs (per-user,
  // per-module, data-changes) gate their queries on the relevant filter
  // being non-empty so we don't fire wide-net fetches.
  const [userIdFilter, setUserIdFilter]               = useState('');
  const [moduleFilter, setModuleFilter]               = useState<typeof MODULES[number] | ''>('');
  const [dcEntityType, setDcEntityType]               = useState<typeof MODULES[number] | ''>('');
  const [dcEntityId,   setDcEntityId]                 = useState('');

  function rangeQueryParams() {
    return { from: isoStart(new Date(range.from)), to: isoEnd(new Date(range.to)) };
  }

  // All report endpoints return the array directly in `data` with pagination
  // in `meta` (Session-77 convention); validatedGet unwraps + validates each.

  // Approvals report
  const approvalsQuery = useQuery<AuditLogDto[]>({
    queryKey: ['audit', 'reports', 'approvals', range],
    queryFn: () => validatedGet('/api/v1/audit/reports/approvals', z.array(AuditLogDtoSchema),
      { params: { ...rangeQueryParams(), size: 100 } }),
  });

  // Login security report
  const loginQuery = useQuery<LoginAuditLogDto[]>({
    queryKey: ['audit', 'reports', 'login-security', range],
    queryFn: () => validatedGet('/api/v1/audit/reports/login-security', z.array(LoginAuditLogDtoSchema),
      { params: { ...rangeQueryParams(), size: 100 } }),
  });

  // User activity report — ranked summary list
  const userActivityQuery = useQuery<UserActivitySummaryDto[]>({
    queryKey: ['audit', 'reports', 'user-activity', range],
    queryFn: () => validatedGet('/api/v1/audit/reports/user-activity', z.array(UserActivitySummaryDtoSchema),
      { params: rangeQueryParams() }),
  });

  // Actions by user — gated on userIdFilter
  const actionsByUserQuery = useQuery<AuditLogDto[]>({
    queryKey: ['audit', 'reports', 'actions-by-user', userIdFilter, range],
    enabled: !!userIdFilter.trim(),
    queryFn: () => validatedGet('/api/v1/audit/reports/actions-by-user', z.array(AuditLogDtoSchema),
      { params: { userId: userIdFilter.trim(), ...rangeQueryParams(), size: 100 } }),
  });

  // Actions by module — gated on moduleFilter
  const actionsByModuleQuery = useQuery<AuditLogDto[]>({
    queryKey: ['audit', 'reports', 'actions-by-module', moduleFilter, range],
    enabled: !!moduleFilter,
    queryFn: () => validatedGet('/api/v1/audit/reports/actions-by-module', z.array(AuditLogDtoSchema),
      { params: { entityType: moduleFilter, ...rangeQueryParams(), size: 100 } }),
  });

  // Data changes — gated on both entityType + entityId
  const dataChangesQuery = useQuery<AuditLogDto[]>({
    queryKey: ['audit', 'reports', 'data-changes', dcEntityType, dcEntityId],
    enabled: !!dcEntityType && !!dcEntityId.trim(),
    queryFn: () => validatedGet('/api/v1/audit/reports/data-changes', z.array(AuditLogDtoSchema),
      { params: { entityType: dcEntityType, entityId: dcEntityId.trim(), size: 100 } }),
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

  const actionsByUserRows = useMemo(() => (actionsByUserQuery.data ?? []).map(e => [
    fmtTimestamp(e.timestamp),
    `${e.entityType}${e.entityId ? ` · ${e.entityId.slice(0, 8)}` : ''}`,
    e.action,
    e.ipAddress ?? '—',
  ]), [actionsByUserQuery.data]);

  const actionsByModuleRows = useMemo(() => (actionsByModuleQuery.data ?? []).map(e => [
    fmtTimestamp(e.timestamp),
    e.entityId ?? '—',
    e.action,
    e.userName ?? e.userId ?? '—',
  ]), [actionsByModuleQuery.data]);

  // Data changes — projection diffs the JSON-encoded oldValue/newValue
  // strings into a row per changed field. Shows "(action)" when the
  // payload is a non-object (e.g. APPROVE with no diff).
  function parseSnapshot(s: string | null | undefined): Record<string, unknown> | null {
    if (!s) return null;
    try {
      const parsed = JSON.parse(s) as unknown;
      return typeof parsed === 'object' && parsed !== null ? parsed as Record<string, unknown> : null;
    } catch { return null; }
  }
  const dataChangesRows = useMemo(() => (dataChangesQuery.data ?? []).flatMap(e => {
    const before = parseSnapshot(e.oldValue);
    const after  = parseSnapshot(e.newValue);
    const keys   = new Set<string>([...Object.keys(before ?? {}), ...Object.keys(after ?? {})]);
    if (keys.size === 0) {
      return [[`(${e.action.toLowerCase()})`, '—', '—', e.userName ?? '—', fmtTimestamp(e.timestamp)]];
    }
    return Array.from(keys).map(k => [
      k,
      before && k in before ? String(before[k]) : '—',
      after  && k in after  ? String(after[k])  : '—',
      e.userName ?? '—',
      fmtTimestamp(e.timestamp),
    ]);
  }), [dataChangesQuery.data]);

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

      {/* ── Actions by User (raw events for a specific userId in the date range) ── */}
      <TabsContent value="actions-by-user" className="mt-4">
        <PageSection
          title="Actions by User"
          description="All audited events performed by a specific user in the selected date range. Enter a userId (UUID) — find one in the Audit Log tab. The User Activity tab shows the aggregated counts."
          actions={<ExportButton filename="audit-actions-by-user" headers={ACTIONS_BY_USER_HEADERS} rows={actionsByUserRows} disabled={!userIdFilter.trim()} />}
        >
          <div className="flex items-end gap-2 mb-3">
            <div className="space-y-1 flex-1 max-w-md">
              <Label htmlFor="user-id-filter" className="text-xs">User ID</Label>
              <Input
                id="user-id-filter"
                placeholder="Paste a userId (UUID) from the Audit Log…"
                value={userIdFilter}
                onChange={e => setUserIdFilter(e.target.value)}
                className="h-8 font-mono text-xs"
              />
            </div>
          </div>
          {!userIdFilter.trim() ? (
            <p className="text-sm text-muted-foreground py-6 text-center">
              Enter a user ID above to load their event history.
            </p>
          ) : actionsByUserQuery.isLoading
            ? <div className="space-y-3"><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>
            : actionsByUserQuery.isError
            ? <p className="text-sm text-destructive">Failed to load events for that user.</p>
            : actionsByUserRows.length === 0
            ? <p className="text-sm text-muted-foreground py-4 text-center">No events found for that user in the selected date range.</p>
            : <Table headers={ACTIONS_BY_USER_HEADERS} rows={actionsByUserRows} />}
        </PageSection>
      </TabsContent>

      {/* ── Actions by Module (raw events filtered by entityType) ──────────────── */}
      <TabsContent value="actions-by-module" className="mt-4">
        <PageSection
          title="Actions by Module"
          description="All audit events for a specific module (entityType) in the selected date range. Backend returns raw events; per-module count breakdowns require a future aggregation endpoint."
          actions={<ExportButton filename="audit-actions-by-module" headers={ACTIONS_BY_MODULE_HEADERS} rows={actionsByModuleRows} disabled={!moduleFilter} />}
        >
          <div className="flex items-end gap-2 mb-3">
            <div className="space-y-1 max-w-xs">
              <Label htmlFor="module-filter" className="text-xs">Module</Label>
              <Select value={moduleFilter || undefined} onValueChange={(v) => setModuleFilter(v as typeof MODULES[number])}>
                <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Select a module" /></SelectTrigger>
                <SelectContent>
                  {MODULES.map(m => <SelectItem key={m} value={m}>{m}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
          </div>
          {!moduleFilter ? (
            <p className="text-sm text-muted-foreground py-6 text-center">
              Select a module above to load its event history.
            </p>
          ) : actionsByModuleQuery.isLoading
            ? <div className="space-y-3"><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>
            : actionsByModuleQuery.isError
            ? <p className="text-sm text-destructive">Failed to load events for that module.</p>
            : actionsByModuleRows.length === 0
            ? <p className="text-sm text-muted-foreground py-4 text-center">No events found for that module in the selected date range.</p>
            : <Table headers={ACTIONS_BY_MODULE_HEADERS} rows={actionsByModuleRows} />}
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

      {/* ── Data Changes (full before/after diff for one entity) ─────────────── */}
      <TabsContent value="data-changes" className="mt-4">
        <PageSection
          title="Data Change History"
          description="Field-level before/after history for a specific entity. Each AUDIT row is expanded into one display row per changed key."
          actions={<ExportButton filename="audit-data-changes" headers={DATA_CHANGES_HEADERS} rows={dataChangesRows} disabled={!dcEntityType || !dcEntityId.trim()} />}
        >
          <div className="flex items-end gap-2 mb-3 flex-wrap">
            <div className="space-y-1 min-w-[140px]">
              <Label htmlFor="dc-entity-type" className="text-xs">Entity Type</Label>
              <Select value={dcEntityType || undefined} onValueChange={(v) => setDcEntityType(v as typeof MODULES[number])}>
                <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Module" /></SelectTrigger>
                <SelectContent>
                  {MODULES.map(m => <SelectItem key={m} value={m}>{m}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1 flex-1 max-w-md">
              <Label htmlFor="dc-entity-id" className="text-xs">Entity ID</Label>
              <Input
                id="dc-entity-id"
                placeholder="UUID — paste from a list page or audit log…"
                value={dcEntityId}
                onChange={e => setDcEntityId(e.target.value)}
                className="h-8 font-mono text-xs"
              />
            </div>
          </div>
          {!dcEntityType || !dcEntityId.trim() ? (
            <p className="text-sm text-muted-foreground py-6 text-center">
              Select an entity type and paste an entity ID to load its change history.
            </p>
          ) : dataChangesQuery.isLoading
            ? <div className="space-y-3"><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>
            : dataChangesQuery.isError
            ? <p className="text-sm text-destructive">Failed to load change history.</p>
            : dataChangesRows.length === 0
            ? <p className="text-sm text-muted-foreground py-4 text-center">No changes recorded for that entity.</p>
            : <Table headers={DATA_CHANGES_HEADERS} rows={dataChangesRows} />}
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
