import { useState } from 'react';
import {
  Badge, Button, Card, CardContent, DataTable, DataTableColumnHeader,
  DataTableRowActions, EmptyState, PageSection, Separator,
} from '@cia/ui';
import { type ColumnDef, type Row } from '@tanstack/react-table';
import TreatySheet from './TreatySheet';

type TreatyType   = 'SURPLUS' | 'QUOTA_SHARE' | 'XOL';
type TreatyStatus = 'ACTIVE' | 'INACTIVE' | 'EXPIRED';

interface TreatyDto {
  id:              string;
  name:            string;
  type:            TreatyType;
  classOfBusiness: string;
  retentionLimit:  number;
  treatyLimit:     number;
  reinsurers:      { name: string; share: number }[];
  year:            number;
  status:          TreatyStatus;
}

const mockTreaties: TreatyDto[] = [
  {
    id: 't1', name: 'Motor Surplus Treaty 2026', type: 'SURPLUS',      classOfBusiness: 'Motor (Private)',  retentionLimit: 2_000_000, treatyLimit: 20_000_000,
    reinsurers: [{ name: 'Munich Re', share: 60 }, { name: 'Swiss Re', share: 40 }], year: 2026, status: 'ACTIVE',
  },
  {
    id: 't2', name: 'Fire QS Treaty 2026',        type: 'QUOTA_SHARE', classOfBusiness: 'Fire & Burglary',  retentionLimit: 0,         treatyLimit: 0,
    reinsurers: [{ name: 'African Re', share: 40 }, { name: 'Continental Re', share: 60 }], year: 2026, status: 'ACTIVE',
  },
  {
    id: 't3', name: 'Marine XOL Layer 1',         type: 'XOL',        classOfBusiness: 'Marine Cargo',      retentionLimit: 5_000_000, treatyLimit: 25_000_000,
    reinsurers: [{ name: 'Lloyd\'s Syndicate', share: 100 }], year: 2026, status: 'ACTIVE',
  },
  {
    id: 't4', name: 'Motor Surplus Treaty 2025',  type: 'SURPLUS',    classOfBusiness: 'Motor (Private)',   retentionLimit: 1_500_000, treatyLimit: 15_000_000,
    reinsurers: [{ name: 'Munich Re', share: 70 }, { name: 'Swiss Re', share: 30 }], year: 2025, status: 'EXPIRED',
  },
];

const TYPE_LABELS: Record<TreatyType, string> = { SURPLUS: 'Surplus', QUOTA_SHARE: 'Quota Share', XOL: 'XOL' };
const TYPE_COLORS: Record<TreatyType, string> = {
  SURPLUS:     'bg-[var(--status-active-bg)] text-[var(--status-active-fg)]',
  QUOTA_SHARE: 'bg-[var(--status-pending-bg)] text-[var(--status-pending-fg)]',
  XOL:         'bg-[var(--status-draft-bg)] text-[var(--status-draft-fg)]',
};
const ST_VARIANT: Record<TreatyStatus, 'active'|'cancelled'|'draft'> = { ACTIVE: 'active', INACTIVE: 'draft', EXPIRED: 'cancelled' };

export default function TreatiesTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<TreatyDto | null>(null);

  const columns: ColumnDef<TreatyDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Treaty" />,
      cell: ({ row }) => (
        <div>
          <p className="font-medium text-foreground">{row.original.name}</p>
          <p className="text-xs text-muted-foreground">{row.original.classOfBusiness} · {row.original.year}</p>
        </div>
      ),
    },
    {
      accessorKey: 'type',
      header: 'Type',
      cell: ({ getValue }) => {
        const t = getValue() as TreatyType;
        return (
          <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-semibold ${TYPE_COLORS[t]}`}>
            {TYPE_LABELS[t]}
          </span>
        );
      },
    },
    {
      accessorKey: 'retentionLimit',
      header: 'Retention',
      cell: ({ row }) => {
        const t = row.original;
        if (t.type === 'QUOTA_SHARE') {
          return <span className="text-sm text-muted-foreground">N/A (% split)</span>;
        }
        return <span className="text-sm tabular-nums">₦{t.retentionLimit.toLocaleString()}</span>;
      },
    },
    {
      accessorKey: 'treatyLimit',
      header: 'Capacity',
      cell: ({ row }) => {
        const t = row.original;
        if (t.type === 'QUOTA_SHARE') return <span className="text-sm text-muted-foreground">—</span>;
        return <span className="text-sm tabular-nums">₦{t.treatyLimit.toLocaleString()}</span>;
      },
    },
    {
      accessorKey: 'reinsurers',
      header: 'Reinsurers',
      cell: ({ getValue }) => {
        const rs = getValue() as { name: string; share: number }[];
        return (
          <div className="text-xs text-muted-foreground space-y-0.5">
            {rs.map(r => <p key={r.name}>{r.name} {r.share}%</p>)}
          </div>
        );
      },
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as TreatyStatus;
        return <Badge variant={ST_VARIANT[s]} className="text-[10px]">{s.toLowerCase()}</Badge>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row as Row<TreatyDto>}
          actions={[
            { label: 'Edit treaty',          onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
            { label: 'Batch reallocation',   onClick: () => {} },
            { label: row.original.status === 'ACTIVE' ? 'Deactivate' : 'Activate', onClick: () => {}, separator: true },
          ]}
        />
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <PageSection
        title="Reinsurance Treaties"
        description="Surplus, Quota Share and XOL treaties define how risks are ceded each year."
        actions={<Button onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Treaty</Button>}
      >
        {mockTreaties.length === 0 ? (
          <EmptyState title="No treaties configured" action={<Button onClick={() => setSheetOpen(true)}>Add Treaty</Button>} />
        ) : (
          <DataTable
            columns={columns}
            data={mockTreaties}
            toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search treaties…' }}
          />
        )}
      </PageSection>

      {/* Treaty type summary cards */}
      <Separator />
      <PageSection title="Treaty Summary" description="Current year capacity by treaty type.">
        <div className="grid grid-cols-3 gap-4">
          {[
            { type: 'SURPLUS',      label: 'Surplus Treaties',     treaties: 1, capacity: '₦20M' },
            { type: 'QUOTA_SHARE',  label: 'Quota Share Treaties',  treaties: 1, capacity: '% split' },
            { type: 'XOL',          label: 'XOL Layers',            treaties: 1, capacity: '₦25M xs ₦5M' },
          ].map(s => (
            <Card key={s.type}>
              <CardContent className="p-4 space-y-1">
                <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-semibold ${TYPE_COLORS[s.type as TreatyType]}`}>
                  {s.label}
                </span>
                <p className="text-2xl font-semibold font-display text-foreground mt-2">{s.treaties}</p>
                <p className="text-xs text-muted-foreground">Active · {s.capacity}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </PageSection>

      <TreatySheet open={sheetOpen} onOpenChange={setSheetOpen} treaty={editing} onSuccess={() => setSheetOpen(false)} />
    </div>
  );
}
