import { useMemo, useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader,
  Input, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import AuditEventDetailSheet, { type AuditLogEntry } from './AuditEventDetailSheet';

// Replace with useList('/api/v1/audit/logs')
const mockAuditLog: AuditLogEntry[] = [
  { id: 'al01', entityType: 'POLICY',      entityId: 'pol1',  entityRef: 'POL-2026-00001', action: 'CREATE',  userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-02-01T09:12:44Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa1', oldValue: null, newValue: { status: 'DRAFT', policyNumber: 'POL-2026-00001', customerId: 'c1', sumInsured: 3500000 } },
  { id: 'al02', entityType: 'POLICY',      entityId: 'pol1',  entityRef: 'POL-2026-00001', action: 'APPROVE', userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-02-01T10:05:18Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa1', oldValue: { status: 'PENDING_APPROVAL' }, newValue: { status: 'ACTIVE' } },
  { id: 'al03', entityType: 'CUSTOMER',    entityId: 'c1',    entityRef: 'Chioma Okafor',  action: 'CREATE',  userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-01-28T14:33:02Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa0', oldValue: null, newValue: { firstName: 'Chioma', lastName: 'Okafor', kycStatus: 'PENDING' } },
  { id: 'al04', entityType: 'CLAIM',       entityId: 'cl1',   entityRef: 'CLM-2026-00001', action: 'CREATE',  userId: 'u2', userName: 'Adaeze Nwosu',    timestamp: '2026-03-12T11:22:07Z', ipAddress: '41.206.32.8',   sessionId: 'sess-bbb1', oldValue: null, newValue: { status: 'REGISTERED', estimatedLoss: 850000 } },
  { id: 'al05', entityType: 'CLAIM',       entityId: 'cl1',   entityRef: 'CLM-2026-00001', action: 'UPDATE',  userId: 'u2', userName: 'Adaeze Nwosu',    timestamp: '2026-03-14T15:44:51Z', ipAddress: '41.206.32.8',   sessionId: 'sess-bbb2', oldValue: { status: 'REGISTERED', reserveAmount: 0 }, newValue: { status: 'PROCESSING', reserveAmount: 650000 } },
  { id: 'al06', entityType: 'CLAIM',       entityId: 'cl1',   entityRef: 'CLM-2026-00001', action: 'APPROVE', userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-03-22T09:07:29Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa3', oldValue: { status: 'PENDING_APPROVAL' }, newValue: { status: 'APPROVED' } },
  { id: 'al07', entityType: 'QUOTE',       entityId: 'q1',    entityRef: 'QOT-2026-00001', action: 'CREATE',  userId: 'u2', userName: 'Adaeze Nwosu',    timestamp: '2026-01-25T10:11:43Z', ipAddress: '41.206.32.8',   sessionId: 'sess-bbb0', oldValue: null, newValue: { status: 'DRAFT', sumInsured: 3500000, premium: 78750 } },
  { id: 'al08', entityType: 'ENDORSEMENT', entityId: 'end1',  entityRef: 'END-2026-00001', action: 'CREATE',  userId: 'u2', userName: 'Adaeze Nwosu',    timestamp: '2026-02-15T14:02:11Z', ipAddress: '41.206.32.8',   sessionId: 'sess-bbb3', oldValue: null, newValue: { type: 'INCREASE_SI', newSumInsured: 4000000 } },
  { id: 'al09', entityType: 'ENDORSEMENT', entityId: 'end1',  entityRef: 'END-2026-00001', action: 'APPROVE', userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-02-16T09:55:00Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa4', oldValue: { status: 'PENDING_APPROVAL' }, newValue: { status: 'APPROVED' } },
  { id: 'al10', entityType: 'RECEIPT',     entityId: 'r1',    entityRef: 'REC-2026-00001', action: 'CREATE',  userId: 'u3', userName: 'Emeka Eze',       timestamp: '2026-03-01T11:30:00Z', ipAddress: '105.112.14.8',  sessionId: 'sess-ccc1', oldValue: null, newValue: { amount: 40000, paymentMethod: 'Bank Transfer' } },
  { id: 'al11', entityType: 'RECEIPT',     entityId: 'r1',    entityRef: 'REC-2026-00001', action: 'APPROVE', userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-03-01T14:10:00Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa5', oldValue: { status: 'PENDING_APPROVAL' }, newValue: { status: 'APPROVED' } },
  { id: 'al12', entityType: 'USER',        entityId: 'u3',    entityRef: 'emeka.eze',      action: 'UPDATE',  userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-02-10T08:47:22Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa2', oldValue: { accessGroupId: 'ag2' }, newValue: { accessGroupId: 'ag3' } },
  { id: 'al13', entityType: 'PAYMENT',     entityId: 'pay1',  entityRef: 'PAY-2026-00001', action: 'APPROVE', userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-02-25T16:22:00Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa6', oldValue: { status: 'PENDING' }, newValue: { status: 'APPROVED' } },
  { id: 'al14', entityType: 'REINSURANCE', entityId: 'ri1',   entityRef: 'FAC-OUT-2026-001', action: 'CREATE', userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-02-15T12:00:00Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa4', oldValue: null, newValue: { status: 'OFFER_SENT', reinsurer: 'Munich Re', sumInsured: 26000000 } },
  { id: 'al15', entityType: 'PARTNER_APP', entityId: 'pa1',   entityRef: 'CoverQuick API', action: 'CREATE',  userId: 'u1', userName: 'Akinwale Nubeero', timestamp: '2026-03-10T10:00:00Z', ipAddress: '197.210.64.12', sessionId: 'sess-aaa7', oldValue: null, newValue: { name: 'CoverQuick API', scopes: ['policies:read', 'quotes:create'] } },
];

const ACTION_VARIANT: Record<string, 'active'|'pending'|'rejected'|'draft'|'cancelled'> = {
  CREATE: 'active', UPDATE: 'pending', DELETE: 'rejected',
  APPROVE: 'active', REJECT: 'rejected', SEND: 'draft', EXPORT: 'draft',
};

const ENTITY_TYPES = ['ALL','POLICY','CLAIM','CUSTOMER','ENDORSEMENT','QUOTE','RECEIPT','PAYMENT','USER','REINSURANCE','PARTNER_APP'];
const ACTIONS      = ['ALL','CREATE','UPDATE','DELETE','APPROVE','REJECT','SEND'];

function exportCSV(data: AuditLogEntry[]) {
  const headers = ['Timestamp','Entity Type','Reference','Action','User','IP Address'];
  const rows    = data.map(e => [e.timestamp, e.entityType, e.entityRef, e.action, e.userName, e.ipAddress]);
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
  const [detail,     setDetail]     = useState<AuditLogEntry | null>(null);
  const [entityType, setEntityType] = useState('ALL');
  const [action,     setAction]     = useState('ALL');
  const [user,       setUser]       = useState('');
  const [entityRef,  setEntityRef]  = useState('');
  const [dateFrom,   setDateFrom]   = useState('');
  const [dateTo,     setDateTo]     = useState('');

  const filtered = useMemo(() => mockAuditLog.filter(e => {
    if (entityType !== 'ALL' && e.entityType !== entityType) return false;
    if (action     !== 'ALL' && e.action     !== action)     return false;
    if (user       && !e.userName.toLowerCase().includes(user.toLowerCase()))       return false;
    if (entityRef  && !e.entityRef.toLowerCase().includes(entityRef.toLowerCase())) return false;
    if (dateFrom   && e.timestamp < dateFrom) return false;
    if (dateTo     && e.timestamp > dateTo + 'T23:59:59Z') return false;
    return true;
  }), [entityType, action, user, entityRef, dateFrom, dateTo]);

  const columns: ColumnDef<AuditLogEntry>[] = [
    {
      accessorKey: 'timestamp',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Timestamp" />,
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground whitespace-nowrap">{(getValue() as string).replace('T', ' ').replace('Z', '')}</span>,
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
          <p className="font-mono text-[11px] text-primary hover:underline underline-offset-2">{row.original.entityRef}</p>
        </button>
      ),
    },
    {
      accessorKey: 'action',
      header: 'Action',
      cell: ({ getValue }) => {
        const a = getValue() as string;
        return <Badge variant={ACTION_VARIANT[a] ?? 'draft'} className="text-[10px]">{a}</Badge>;
      },
    },
    {
      accessorKey: 'userName',
      header: 'User',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span>,
    },
    {
      accessorKey: 'ipAddress',
      header: 'IP Address',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
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
            className="h-8 text-xs" placeholder="Entity ID or reference…"
            value={entityRef} onChange={(e) => setEntityRef(e.target.value)}
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

        <DataTable
          columns={columns}
          data={filtered}
          toolbar={{ searchColumn: 'entityType', searchPlaceholder: 'Search…' }}
        />
      </PageSection>

      <AuditEventDetailSheet
        open={detail !== null}
        onOpenChange={(v) => { if (!v) setDetail(null); }}
        entry={detail}
      />
    </>
  );
}
