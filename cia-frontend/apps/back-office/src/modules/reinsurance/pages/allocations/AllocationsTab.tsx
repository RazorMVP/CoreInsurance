import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection,
} from '@cia/ui';
import { type ColumnDef, type Row } from '@tanstack/react-table';

type AllocStatus = 'AUTO_ALLOCATED' | 'CONFIRMED' | 'APPROVED' | 'EXCESS_CAPACITY';

interface AllocationDto {
  id:             string;
  policyNumber:   string;
  classOfBusiness:string;
  sumInsured:     number;
  retentionAmount:number;
  cedingAmount:   number;
  treatyName:     string;
  treatyType:     string;
  reinsurers:     string;
  status:         AllocStatus;
  treatyYear:     number;
}

const mockAllocations: AllocationDto[] = [
  { id: 'a1', policyNumber: 'POL-2026-00001', classOfBusiness: 'Motor (Private)',  sumInsured: 3_500_000,  retentionAmount: 2_000_000, cedingAmount: 1_500_000,  treatyName: 'Motor Surplus 2026', treatyType: 'Surplus',      reinsurers: 'Munich Re 60%, Swiss Re 40%',        status: 'CONFIRMED',       treatyYear: 2026 },
  { id: 'a2', policyNumber: 'POL-2026-00002', classOfBusiness: 'Fire & Burglary',  sumInsured: 15_000_000, retentionAmount: 9_000_000, cedingAmount: 6_000_000,  treatyName: 'Fire QS 2026',       treatyType: 'Quota Share',  reinsurers: 'African Re 40%, Continental Re 60%', status: 'APPROVED',        treatyYear: 2026 },
  { id: 'a3', policyNumber: 'POL-2026-00003', classOfBusiness: 'Motor (Private)',  sumInsured: 2_200_000,  retentionAmount: 2_000_000, cedingAmount: 200_000,    treatyName: 'Motor Surplus 2026', treatyType: 'Surplus',      reinsurers: 'Munich Re 60%, Swiss Re 40%',        status: 'AUTO_ALLOCATED',  treatyYear: 2026 },
  { id: 'a4', policyNumber: 'POL-2026-00004', classOfBusiness: 'Marine Cargo',    sumInsured: 8_000_000,  retentionAmount: 5_000_000, cedingAmount: 3_000_000,  treatyName: 'Marine XOL 2026',    treatyType: 'XOL',          reinsurers: "Lloyd's 100%",                       status: 'AUTO_ALLOCATED',  treatyYear: 2026 },
  { id: 'a5', policyNumber: 'POL-2026-00005', classOfBusiness: 'Fire & Burglary',  sumInsured: 35_000_000, retentionAmount: 9_000_000, cedingAmount: 26_000_000, treatyName: 'Fire QS 2026',       treatyType: 'Quota Share',  reinsurers: 'Pending FAC',                        status: 'EXCESS_CAPACITY', treatyYear: 2026 },
];

const stVariant: Record<AllocStatus, 'active'|'pending'|'draft'|'rejected'> = {
  AUTO_ALLOCATED:  'draft',
  CONFIRMED:       'pending',
  APPROVED:        'active',
  EXCESS_CAPACITY: 'rejected',
};

const stLabel: Record<AllocStatus, string> = {
  AUTO_ALLOCATED:  'Auto-allocated',
  CONFIRMED:       'Confirmed',
  APPROVED:        'Approved',
  EXCESS_CAPACITY: 'Excess Capacity',
};

export default function AllocationsTab() {
  const pendingConfirmation = mockAllocations.filter(a => a.status === 'AUTO_ALLOCATED').length;
  const excessCapacity      = mockAllocations.filter(a => a.status === 'EXCESS_CAPACITY').length;

  const columns: ColumnDef<AllocationDto>[] = [
    {
      accessorKey: 'policyNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Policy" />,
      cell: ({ getValue }) => <span className="font-mono text-xs text-primary">{getValue() as string}</span>,
    },
    {
      accessorKey: 'classOfBusiness',
      header: 'Class',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'sumInsured',
      header: 'Sum Insured',
      cell: ({ getValue }) => <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'retentionAmount',
      header: 'Retention',
      cell: ({ getValue }) => <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'cedingAmount',
      header: 'Ceding',
      cell: ({ getValue }) => <span className="text-sm font-medium tabular-nums text-primary">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'treatyName',
      header: 'Treaty',
      cell: ({ row }) => (
        <div>
          <p className="text-sm">{row.original.treatyName}</p>
          <p className="text-xs text-muted-foreground">{row.original.reinsurers}</p>
        </div>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const s = getValue() as AllocStatus;
        return <Badge variant={stVariant[s]} className="text-[10px] whitespace-nowrap">{stLabel[s]}</Badge>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row as Row<AllocationDto>}
          actions={[
            ...(row.original.status === 'AUTO_ALLOCATED'  ? [{ label: 'Confirm allocation', onClick: () => {} }] : []),
            ...(row.original.status === 'CONFIRMED'       ? [{ label: 'Approve',            onClick: () => {} }, { label: 'Reject', onClick: () => {}, className: 'text-destructive' }] : []),
            ...(row.original.status === 'EXCESS_CAPACITY' ? [{ label: 'Create FAC cover',   onClick: () => {} }] : []),
            { label: 'View policy', onClick: () => {} },
          ]}
        />
      ),
    },
  ];

  return (
    <div className="space-y-5">
      {/* Alert banners */}
      {(pendingConfirmation > 0 || excessCapacity > 0) && (
        <div className="flex gap-3 flex-wrap">
          {pendingConfirmation > 0 && (
            <div className="flex items-center gap-3 rounded-lg border bg-[var(--status-draft-bg)] px-4 py-3 flex-1 min-w-[280px]">
              <Badge variant="draft" className="text-[10px] shrink-0">{pendingConfirmation}</Badge>
              <p className="text-sm text-foreground">allocations awaiting confirmation</p>
              <Button size="sm" className="ml-auto shrink-0">Confirm All</Button>
            </div>
          )}
          {excessCapacity > 0 && (
            <div className="flex items-center gap-3 rounded-lg border bg-[var(--status-rejected-bg)] px-4 py-3 flex-1 min-w-[280px]">
              <Badge variant="rejected" className="text-[10px] shrink-0">{excessCapacity}</Badge>
              <p className="text-sm text-foreground">policies exceed gross treaty capacity — FAC required</p>
              <Button size="sm" variant="outline" className="ml-auto shrink-0">Create FAC</Button>
            </div>
          )}
        </div>
      )}

      <PageSection
        title="RI Allocations"
        description="Automatic treaty allocations per policy. Confirm auto-allocated items and approve for booking."
        actions={
          <Button variant="outline" size="sm">Batch Reallocation</Button>
        }
      >
        <DataTable
          columns={columns}
          data={mockAllocations}
          toolbar={{ searchColumn: 'policyNumber', searchPlaceholder: 'Search by policy…' }}
        />
      </PageSection>
    </div>
  );
}
