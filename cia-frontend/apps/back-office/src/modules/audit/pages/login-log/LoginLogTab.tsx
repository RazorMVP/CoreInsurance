import { useMemo, useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader,
  Input, PageSection, Skeleton,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import {
  apiClient, LoginAuditLogDtoSchema, pageSchema,
  type LoginAuditLogDto, type LoginEventType,
} from '@cia/api-client';
import { formatTimestamp } from '@/lib/format';

// allow-mock: fallback while /audit/login-logs is in flight
const mockLoginLog: LoginAuditLogDto[] = [
  { id: 'll01', userId: 'u1', userName: 'Akinwale Nubeero', eventType: 'LOGIN',          ipAddress: '197.210.64.12', userAgent: 'Chrome/124 (Windows)',    timestamp: '2026-04-24T08:01:12Z', success: true },
  { id: 'll02', userId: 'u2', userName: 'Adaeze Nwosu',    eventType: 'LOGIN',          ipAddress: '41.206.32.8',   userAgent: 'Chrome/124 (macOS)',      timestamp: '2026-04-24T08:15:44Z', success: true },
  { id: 'll03', userId: 'u3', userName: 'Emeka Eze',       eventType: 'LOGIN_FAILED',   ipAddress: '154.113.22.9',  userAgent: 'Firefox/125 (Ubuntu)',    timestamp: '2026-04-24T08:22:07Z', success: false, failureReason: 'Invalid password' },
  { id: 'll04', userId: 'u3', userName: 'Emeka Eze',       eventType: 'LOGIN_FAILED',   ipAddress: '154.113.22.9',  userAgent: 'Firefox/125 (Ubuntu)',    timestamp: '2026-04-24T08:22:41Z', success: false, failureReason: 'Invalid password' },
  { id: 'll05', userId: 'u3', userName: 'Emeka Eze',       eventType: 'LOGIN_FAILED',   ipAddress: '154.113.22.9',  userAgent: 'Firefox/125 (Ubuntu)',    timestamp: '2026-04-24T08:23:08Z', success: false, failureReason: 'Invalid password' },
  { id: 'll06', userId: 'u3', userName: 'Emeka Eze',       eventType: 'ACCOUNT_LOCKED', ipAddress: '154.113.22.9',  userAgent: 'Firefox/125 (Ubuntu)',    timestamp: '2026-04-24T08:23:09Z', success: false, failureReason: 'Too many failed attempts (3/3)' },
  { id: 'll07', userId: 'u1', userName: 'Akinwale Nubeero', eventType: 'LOGOUT',         ipAddress: '197.210.64.12', userAgent: 'Chrome/124 (Windows)',    timestamp: '2026-04-24T12:05:00Z', success: true },
  { id: 'll08', userId: 'u2', userName: 'Adaeze Nwosu',    eventType: 'LOGOUT',         ipAddress: '41.206.32.8',   userAgent: 'Chrome/124 (macOS)',      timestamp: '2026-04-24T13:30:00Z', success: true },
  { id: 'll09', userId: 'u4', userName: 'Ngozi Adeyemi',   eventType: 'PASSWORD_RESET', ipAddress: '41.206.52.1',   userAgent: 'Safari/17 (iPhone)',     timestamp: '2026-04-23T16:42:55Z', success: true },
  { id: 'll10', userId: 'u1', userName: 'Akinwale Nubeero', eventType: 'LOGIN',          ipAddress: '197.210.64.12', userAgent: 'Chrome/124 (Windows)',    timestamp: '2026-04-23T08:10:00Z', success: true },
  { id: 'll11', userId: 'u5', userName: 'Chukwudi Obi',    eventType: 'LOGIN',          ipAddress: '102.89.3.44',   userAgent: 'Chrome/124 (Android)',    timestamp: '2026-04-22T21:44:10Z', success: true },
  { id: 'll12', userId: 'u5', userName: 'Chukwudi Obi',    eventType: 'LOGOUT',         ipAddress: '102.89.3.44',   userAgent: 'Chrome/124 (Android)',    timestamp: '2026-04-22T23:01:00Z', success: true },
];

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
    queryFn: async () => {
      // Backend returns Page<LoginAuditLogResponse>; unwrap content[].
      const res = await apiClient.get('/api/v1/audit/login-logs');
      const page = pageSchema(LoginAuditLogDtoSchema).parse(res.data.data);
      return page.content;
    },
  });
  const loginLog = loginQuery.data ?? mockLoginLog;
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
