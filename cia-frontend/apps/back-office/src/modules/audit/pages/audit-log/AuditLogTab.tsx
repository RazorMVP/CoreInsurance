import { useEffect, useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader,
  Input, PageSection, Skeleton,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import {
  validatedList, AuditLogDtoSchema,
  type AuditAction, type AuditLogDto,
} from '@cia/api-client';
import { useServerPagination } from '@/lib/use-server-pagination';
import { useDebouncedValue } from '@/lib/use-debounced-value';
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
  const [detail, setDetail] = useState<AuditLogDto | null>(null);

  // Server-side filter + pagination (URL-synced). The backend AuditLogFilter
  // supports entityType/action (exact), userId/entityId (exact) and a
  // timestamp range (from/to) — see AuditQueryService.buildSpec. The two
  // free-text inputs are therefore EXACT match, not substring (a substring `q`
  // would need adding to AuditLogFilter — tracked: audit-server-substring).
  const { page, size, sort, filters, setPage, setSize, setSort, setFilter } =
    useServerPagination({ defaultSize: 20, defaultSort: 'timestamp,desc' });
  const entityType = filters.entityType ?? 'ALL';
  const action     = filters.action     ?? 'ALL';
  const [userInput,     setUserInput]     = useState(filters.userId   ?? '');
  const [entityIdInput, setEntityIdInput] = useState(filters.entityId ?? '');
  const debouncedUser     = useDebouncedValue(userInput, 350);
  const debouncedEntityId = useDebouncedValue(entityIdInput, 350);
  useEffect(() => { setFilter('userId', debouncedUser); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [debouncedUser]);
  useEffect(() => { setFilter('entityId', debouncedEntityId); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [debouncedEntityId]);

  const params = {
    page, size, sort,
    ...(entityType !== 'ALL' ? { entityType } : {}),
    ...(action     !== 'ALL' ? { action } : {}),
    ...(filters.userId   ? { userId: filters.userId } : {}),
    ...(filters.entityId ? { entityId: filters.entityId } : {}),
    ...(filters.from ? { from: `${filters.from}T00:00:00Z` } : {}),
    ...(filters.to   ? { to:   `${filters.to}T23:59:59Z` } : {}),
  };
  // No fabricated fallback: this is a compliance surface, so a failed load must
  // read as empty-with-error, never as plausible-but-fake audit rows.
  const auditQuery = useQuery({
    queryKey: ['audit', 'logs', params],
    queryFn: () => validatedList('/api/v1/audit/logs', AuditLogDtoSchema, { params }),
  });
  const rows  = auditQuery.data?.data ?? [];
  const total = auditQuery.data?.meta.total ?? 0;

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
            {/* Exports the current page only (server-paginated). A full-set
                server export is a follow-up — tracked: audit-csv-server-export. */}
            <Button variant="outline" size="sm" onClick={() => exportCSV(rows)}>
              Export CSV ({rows.length})
            </Button>
          </div>
        }
      >
        {/* Filter bar — all server-side (URL-synced). */}
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3 mb-4">
          <Select value={entityType} onValueChange={(v) => setFilter('entityType', v === 'ALL' ? '' : v)}>
            <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Entity type" /></SelectTrigger>
            <SelectContent>{ENTITY_TYPES.map(t => <SelectItem key={t} value={t}>{t === 'ALL' ? 'All entities' : t}</SelectItem>)}</SelectContent>
          </Select>
          <Select value={action} onValueChange={(v) => setFilter('action', v === 'ALL' ? '' : v)}>
            <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Action" /></SelectTrigger>
            <SelectContent>{ACTIONS.map(a => <SelectItem key={a} value={a}>{a === 'ALL' ? 'All actions' : a}</SelectItem>)}</SelectContent>
          </Select>
          <Input
            className="h-8 text-xs" placeholder="User ID (exact)…"
            value={userInput} onChange={(e) => setUserInput(e.target.value)}
          />
          <Input
            className="h-8 text-xs" placeholder="Entity ID (exact)…"
            value={entityIdInput} onChange={(e) => setEntityIdInput(e.target.value)}
          />
          <Input
            className="h-8 text-xs" type="date"
            value={filters.from ?? ''} onChange={(e) => setFilter('from', e.target.value)}
          />
          <Input
            className="h-8 text-xs" type="date"
            value={filters.to ?? ''} onChange={(e) => setFilter('to', e.target.value)}
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
            data={rows}
            serverPagination={{ page, size, total, onPageChange: setPage, onSizeChange: setSize, sort, onSortChange: setSort }}
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
