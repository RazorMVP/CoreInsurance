import { useMemo, useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader,
  Input, PageSection, Skeleton,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  validatedGet, AuditLogDtoSchema,
  type AuditAction, type AuditLogDto,
} from '@cia/api-client';
import AuditEventDetailSheet from './AuditEventDetailSheet';
import { formatTimestamp } from '@/lib/format';

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

const ENTITY_TYPES = ['ALL','POLICY','CLAIM','CUSTOMER','ENDORSEMENT','QUOTE','RECEIPT','PAYMENT','USER','REINSURANCE','PARTNER_APP'];
const ACTIONS      = ['ALL','CREATE','UPDATE','DELETE','APPROVE','REJECT','SUBMIT','SEND','CANCEL','REVERSE','EXECUTE'];

function exportCSV(data: AuditLogDto[]) {
  const headers = ['Timestamp', 'Entity Type', 'Entity ID', 'Action', 'User', 'IP Address'];
  const rows    = data.map(e => [
    e.timestamp,
    e.entityType,
    e.entityId ?? '',
    e.action,
    e.userName ?? '',
    e.ipAddress ?? '',
  ]);
  const csv     = [headers, ...rows]
    .map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(','))
    .join('\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href = url; a.download = `audit-log-${new Date().toISOString().split('T')[0]}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

export default function AuditLogTab() {
  const auditQuery = useQuery<AuditLogDto[]>({
    queryKey: ['audit', 'logs'],
    // List endpoint returns the array directly in `data` with pagination in
    // `meta` (Session-77 convention). validatedGet unwraps + validates it.
    queryFn: () => validatedGet('/api/v1/audit/logs', z.array(AuditLogDtoSchema)),
  });
  // No fabricated fallback: this is a compliance surface, so a failed load must
  // read as empty-with-error, never as plausible-but-fake audit rows.
  const auditLog = auditQuery.data ?? [];
  const [detail,     setDetail]     = useState<AuditLogDto | null>(null);
  const [entityType, setEntityType] = useState('ALL');
  const [action,     setAction]     = useState('ALL');
  const [user,       setUser]       = useState('');
  const [entityIdQ,  setEntityIdQ]  = useState('');
  const [dateFrom,   setDateFrom]   = useState('');
  const [dateTo,     setDateTo]     = useState('');

  const filtered = useMemo(() => auditLog.filter(e => {
    if (entityType !== 'ALL' && e.entityType !== entityType) return false;
    if (action     !== 'ALL' && e.action     !== action)     return false;
    if (user       && !(e.userName ?? '').toLowerCase().includes(user.toLowerCase())) return false;
    if (entityIdQ  && !(e.entityId  ?? '').toLowerCase().includes(entityIdQ.toLowerCase())) return false;
    if (dateFrom   && e.timestamp < dateFrom) return false;
    if (dateTo     && e.timestamp > dateTo + 'T23:59:59Z') return false;
    return true;
  }), [auditLog, entityType, action, user, entityIdQ, dateFrom, dateTo]);

  const columns: ColumnDef<AuditLogDto>[] = [
    {
      accessorKey: 'timestamp',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Timestamp" />,
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground whitespace-nowrap">{formatTimestamp(getValue() as string | null | undefined)}</span>,
    },
    {
      accessorKey: 'entityType',
      header: 'Entity',
      cell: ({ row }) => (
        <button
          type="button"
          className="text-left"
          onClick={() => setDetail(row.original)}
        >
          <p className="text-xs font-medium text-foreground">{row.original.entityType}</p>
          <p className="font-mono text-[11px] text-primary hover:underline underline-offset-2">
            {row.original.entityId ? row.original.entityId.slice(0, 8) : '—'}
          </p>
        </button>
      ),
    },
    {
      accessorKey: 'action',
      header: 'Action',
      cell: ({ getValue }) => {
        const a = getValue() as AuditAction;
        return <Badge variant={ACTION_VARIANT[a] ?? 'draft'} className="text-[10px]">{a}</Badge>;
      },
    },
    {
      accessorKey: 'userName',
      header: 'User',
      cell: ({ getValue }) => <span className="text-sm">{(getValue() as string | null) ?? '—'}</span>,
    },
    {
      accessorKey: 'ipAddress',
      header: 'IP Address',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{(getValue() as string | null) ?? '—'}</span>,
    },
  ];

  return (
    <>
      <PageSection
        title="Audit Log"
        description="Complete record of all create, update, delete, approve and send operations."
        actions={
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => exportCSV(filtered)}>
              Export CSV ({filtered.length})
            </Button>
          </div>
        }
      >
        {/* Filter bar */}
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3 mb-4">
          <Select value={entityType} onValueChange={setEntityType}>
            <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Entity type" /></SelectTrigger>
            <SelectContent>{ENTITY_TYPES.map(t => <SelectItem key={t} value={t}>{t === 'ALL' ? 'All entities' : t}</SelectItem>)}</SelectContent>
          </Select>
          <Select value={action} onValueChange={setAction}>
            <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Action" /></SelectTrigger>
            <SelectContent>{ACTIONS.map(a => <SelectItem key={a} value={a}>{a === 'ALL' ? 'All actions' : a}</SelectItem>)}</SelectContent>
          </Select>
          <Input
            className="h-8 text-xs" placeholder="Filter by user…"
            value={user} onChange={(e) => setUser(e.target.value)}
          />
          <Input
            className="h-8 text-xs" placeholder="Entity ID…"
            value={entityIdQ} onChange={(e) => setEntityIdQ(e.target.value)}
          />
          <Input
            className="h-8 text-xs" type="date"
            value={dateFrom} onChange={(e) => setDateFrom(e.target.value)}
          />
          <Input
            className="h-8 text-xs" type="date"
            value={dateTo} onChange={(e) => setDateTo(e.target.value)}
          />
        </div>

        {auditQuery.isLoading ? (
          <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
        ) : auditQuery.isError ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/5 px-3 py-2.5 text-sm text-destructive">
            Failed to load the audit log. This view shows no records rather than sample data — retry, or check the API connection.
          </div>
        ) : (
          <DataTable
            columns={columns}
            data={filtered}
            toolbar={{ searchColumn: 'entityType', searchPlaceholder: 'Search…' }}
          />
        )}
      </PageSection>

      <AuditEventDetailSheet
        open={detail !== null}
        onOpenChange={(v) => { if (!v) setDetail(null); }}
        entry={detail}
      />
    </>
  );
}
