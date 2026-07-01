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
  validatedGet, LoginAuditLogDtoSchema,
  type LoginAuditLogDto, type LoginEventType,
} from '@cia/api-client';
import { formatTimestamp } from '@/lib/format';

const EVENT_VARIANT: Record<LoginEventType, 'active'|'pending'|'rejected'|'draft'|'cancelled'> = {
  LOGIN:          'active',
  LOGOUT:         'draft',
  LOGIN_FAILED:   'rejected',
  PASSWORD_RESET: 'pending',
  ACCOUNT_LOCKED: 'rejected',
};

const EVENT_TYPES: { value: string; label: string }[] = [
  { value: 'ALL',            label: 'All events' },
  { value: 'LOGIN',          label: 'Login' },
  { value: 'LOGOUT',         label: 'Logout' },
  { value: 'LOGIN_FAILED',   label: 'Failed login' },
  { value: 'PASSWORD_RESET', label: 'Password reset' },
  { value: 'ACCOUNT_LOCKED', label: 'Account locked' },
];

function exportCSV(data: LoginAuditLogDto[]) {
  const headers = ['Timestamp', 'User', 'Event', 'Success', 'IP Address', 'Failure Reason'];
  const rows    = data.map(e => [
    e.timestamp,
    e.userName ?? e.userId ?? '',
    e.eventType,
    e.success ? 'Yes' : 'No',
    e.ipAddress ?? '',
    e.failureReason ?? '',
  ]);
  const csv     = [headers, ...rows]
    .map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(','))
    .join('\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href = url; a.download = `login-log-${new Date().toISOString().split('T')[0]}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

export default function LoginLogTab() {
  const loginQuery = useQuery<LoginAuditLogDto[]>({
    queryKey: ['audit', 'login-logs'],
    // List endpoint returns the array directly in `data` with pagination in
    // `meta` (Session-77 convention). validatedGet unwraps + validates it.
    queryFn: () => validatedGet('/api/v1/audit/login-logs', z.array(LoginAuditLogDtoSchema)),
  });
  // No fabricated fallback: a failed load reads as empty-with-error, never fake events.
  const loginLog = loginQuery.data ?? [];
  const [eventType, setEventType] = useState('ALL');
  const [user,      setUser]      = useState('');
  const [dateFrom,  setDateFrom]  = useState('');
  const [dateTo,    setDateTo]    = useState('');

  const filtered = useMemo(() => loginLog.filter(e => {
    if (eventType !== 'ALL' && e.eventType !== eventType) return false;
    const haystack = `${e.userName ?? ''} ${e.userId ?? ''}`.toLowerCase();
    if (user && !haystack.includes(user.toLowerCase())) return false;
    if (dateFrom && e.timestamp < dateFrom) return false;
    if (dateTo   && e.timestamp > dateTo + 'T23:59:59Z') return false;
    return true;
  }), [loginLog, eventType, user, dateFrom, dateTo]);

  const columns: ColumnDef<LoginAuditLogDto>[] = [
    {
      accessorKey: 'timestamp',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Timestamp" />,
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground whitespace-nowrap">{formatTimestamp(getValue() as string | null | undefined)}</span>,
    },
    {
      accessorKey: 'userName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="User" />,
      cell: ({ row }) => (
        <div>
          <p className="text-sm font-medium text-foreground">{row.original.userName ?? '—'}</p>
          {row.original.userId && (
            <p className="font-mono text-xs text-muted-foreground">{row.original.userId.slice(0, 8)}</p>
          )}
        </div>
      ),
    },
    {
      accessorKey: 'eventType',
      header: 'Event',
      cell: ({ getValue }) => {
        const t = getValue() as LoginEventType;
        return <Badge variant={EVENT_VARIANT[t]} className="text-[10px] whitespace-nowrap">{t.replace('_', ' ').toLowerCase()}</Badge>;
      },
    },
    {
      accessorKey: 'success',
      header: 'Status',
      cell: ({ getValue }) => {
        const ok = getValue() as boolean;
        return <Badge variant={ok ? 'active' : 'rejected'} className="text-[10px]">{ok ? 'Success' : 'Failed'}</Badge>;
      },
    },
    {
      accessorKey: 'ipAddress',
      header: 'IP Address',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{(getValue() as string | null) ?? '—'}</span>,
    },
    {
      accessorKey: 'userAgent',
      header: 'Device',
      cell: ({ getValue }) => <span className="text-xs text-muted-foreground">{(getValue() as string | null) ?? '—'}</span>,
    },
    {
      accessorKey: 'failureReason',
      header: 'Failure Reason',
      cell: ({ getValue }) => {
        const v = getValue() as string | null | undefined;
        return v ? <span className="text-xs text-destructive">{v}</span> : null;
      },
    },
  ];

  return (
    <PageSection
      title="Login & Session Log"
      description="Authentication events — logins, logouts, failures, password resets and lockouts."
      actions={
        <Button variant="outline" size="sm" onClick={() => exportCSV(filtered)}>
          Export CSV ({filtered.length})
        </Button>
      }
    >
      {/* Filter bar */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
        <Select value={eventType} onValueChange={setEventType}>
          <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Event type" /></SelectTrigger>
          <SelectContent>
            {EVENT_TYPES.map(t => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}
          </SelectContent>
        </Select>
        <Input
          className="h-8 text-xs" placeholder="Filter by user or email…"
          value={user} onChange={(e) => setUser(e.target.value)}
        />
        <Input className="h-8 text-xs" type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)} />
        <Input className="h-8 text-xs" type="date" value={dateTo}   onChange={(e) => setDateTo(e.target.value)} />
      </div>

      {loginQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : loginQuery.isError ? (
        <div className="rounded-md border border-destructive/50 bg-destructive/5 px-3 py-2.5 text-sm text-destructive">
          Failed to load login &amp; session events. This view shows no records rather than sample data — retry, or check the API connection.
        </div>
      ) : (
        <DataTable
          columns={columns}
          data={filtered}
          toolbar={{ searchColumn: 'userName', searchPlaceholder: 'Search by user…' }}
        />
      )}
    </PageSection>
  );
}
