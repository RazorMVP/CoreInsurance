import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { PolicyDto } from '@cia/api-client';
import CreatePolicySheet from './create/CreatePolicySheet';

const mockPolicies: PolicyDto[] = [
  { id: 'pol1', policyNumber: 'POL-2026-00001', quoteId: 'q4',  customerId: 'c1', customerName: 'Chioma Okafor',     productId: 'p1', productName: 'Private Motor Comprehensive', classOfBusinessId: '1', classOfBusinessName: 'Motor (Private)',  businessType: 'DIRECT', status: 'ACTIVE',           sumInsured: 3_500_000,  premium: 78_750,  netPremium: 78_750,  startDate: '2026-02-01', endDate: '2027-02-01', naicomUid: 'NMC-2026-00001', niidUid: 'NIID-2026-00001', documentPath: '/docs/pol1.pdf', debitNoteId: 'dn1', createdAt: '2026-01-30', updatedAt: '2026-02-01' },
  { id: 'pol2', policyNumber: 'POL-2026-00002', quoteId: undefined, customerId: 'c2', customerName: 'Alaba Trading Co.', productId: 'p3', productName: 'Fire & Burglary Standard',    classOfBusinessId: '3', classOfBusinessName: 'Fire & Burglary', businessType: 'DIRECT', status: 'PENDING_APPROVAL',  sumInsured: 15_000_000, premium: 120_000, netPremium: 115_000, startDate: '2026-03-01', endDate: '2027-03-01', naicomUid: undefined, niidUid: undefined, documentPath: undefined, debitNoteId: undefined, createdAt: '2026-02-05', updatedAt: '2026-02-06' },
  { id: 'pol3', policyNumber: 'POL-2026-00003', quoteId: undefined, customerId: 'c3', customerName: 'Emeka Eze',         productId: 'p1', productName: 'Private Motor Comprehensive', classOfBusinessId: '1', classOfBusinessName: 'Motor (Private)',  businessType: 'DIRECT', status: 'DRAFT',            sumInsured: 2_200_000,  premium: 49_500,  netPremium: 49_500,  startDate: '2026-03-15', endDate: '2027-03-15', naicomUid: undefined, niidUid: undefined, documentPath: undefined, debitNoteId: undefined, createdAt: '2026-02-10', updatedAt: '2026-02-10' },
  { id: 'pol4', policyNumber: 'POL-2025-00088', quoteId: undefined, customerId: 'c5', customerName: 'Ngozi Adeyemi',     productId: 'p3', productName: 'Fire & Burglary Standard',    classOfBusinessId: '3', classOfBusinessName: 'Fire & Burglary', businessType: 'DIRECT', status: 'EXPIRED',          sumInsured: 5_000_000,  premium: 40_000,  netPremium: 40_000,  startDate: '2025-03-01', endDate: '2026-03-01', naicomUid: 'NMC-2025-00088', niidUid: undefined, documentPath: '/docs/pol4.pdf', debitNoteId: 'dn4', createdAt: '2025-02-28', updatedAt: '2026-03-01' },
  { id: 'pol5', policyNumber: 'POL-2026-00004', quoteId: undefined, customerId: 'c2', customerName: 'Alaba Trading Co.', productId: 'p4', productName: 'Marine Cargo Open Cover',     classOfBusinessId: '4', classOfBusinessName: 'Marine Cargo',    businessType: 'DIRECT', status: 'ACTIVE',           sumInsured: 8_000_000,  premium: 60_000,  netPremium: 60_000,  startDate: '2026-01-15', endDate: '2027-01-15', naicomUid: 'NMC-2026-00004', niidUid: undefined, documentPath: '/docs/pol5.pdf', debitNoteId: 'dn5', createdAt: '2026-01-15', updatedAt: '2026-01-15' },
];

const statusVariant: Record<PolicyDto['status'], 'active' | 'pending' | 'draft' | 'cancelled' | 'rejected'> = {
  ACTIVE:           'active',
  PENDING_APPROVAL: 'pending',
  DRAFT:            'draft',
  EXPIRED:          'cancelled',
  CANCELLED:        'rejected',
  LAPSED:           'draft',
};

function NaicomBadge({ uid }: { uid?: string }) {
  if (!uid)  return <Badge variant="pending" className="text-[10px] font-mono">PENDING</Badge>;
  return <span className="font-mono text-xs text-foreground">{uid}</span>;
}

export default function PolicyListPage() {
  const navigate = useNavigate();
  const [createOpen, setCreateOpen] = useState(false);

  const columns: ColumnDef<PolicyDto>[] = [
    {
      accessorKey: 'policyNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Policy No." />,
      cell: ({ row }) => (
        <button
          className="font-mono text-xs text-primary hover:underline"
          onClick={() => navigate(`/policies/${row.original.id}`)}
        >
          {row.original.policyNumber}
        </button>
      ),
    },
    {
      accessorKey: 'customerName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Customer" />,
      cell: ({ getValue }) => <span className="text-sm font-medium">{getValue() as string}</span>,
    },
    {
      accessorKey: 'productName',
      header: 'Product / Class',
      cell: ({ row }) => (
        <div>
          <p className="text-sm">{row.original.productName}</p>
          <p className="text-xs text-muted-foreground">{row.original.classOfBusinessName}</p>
        </div>
      ),
    },
    {
      accessorKey: 'sumInsured',
      header: 'Sum Insured',
      cell: ({ getValue }) => (
        <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>
      ),
    },
    {
      accessorKey: 'netPremium',
      header: 'Net Premium',
      cell: ({ getValue }) => (
        <span className="text-sm font-medium tabular-nums">₦{(getValue() as number).toLocaleString()}</span>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const s = getValue() as PolicyDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase().replace('_', ' ')}</Badge>;
      },
    },
    {
      accessorKey: 'naicomUid',
      header: 'NAICOM UID',
      cell: ({ getValue }) => <NaicomBadge uid={getValue() as string | undefined} />,
    },
    {
      accessorKey: 'endDate',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Expiry" />,
      cell: ({ getValue }) => (
        <span className="text-sm text-muted-foreground">{getValue() as string}</span>
      ),
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const { status, id } = row.original;
        return (
          <DataTableRowActions
            row={row}
            actions={[
              { label: 'View details',         onClick: () => navigate(`/policies/${id}`) },
              ...(status === 'DRAFT'            ? [{ label: 'Submit for approval', onClick: () => {} }] : []),
              ...(status === 'ACTIVE'           ? [{ label: 'Add endorsement',     onClick: () => {} }] : []),
              ...(status === 'ACTIVE'           ? [{ label: 'Register claim',      onClick: () => {} }] : []),
              ...(status === 'PENDING_APPROVAL' ? [{ label: 'Approve policy',      onClick: () => {} }] : []),
              { label: 'Download document',    onClick: () => {}, separator: status !== 'DRAFT' },
            ].filter(Boolean)}
          />
        );
      },
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Policies"
        description="Manage the full policy lifecycle from issuance through renewal."
        actions={
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button>New Policy ▾</Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => setCreateOpen(true)}>
                Convert from approved quote
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => setCreateOpen(true)}>
                Create without quote
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        }
      />

      {mockPolicies.length === 0 ? (
        <EmptyState
          title="No policies yet"
          description="Issue your first policy by converting an approved quote or creating one directly."
          action={<Button onClick={() => setCreateOpen(true)}>New Policy</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={mockPolicies}
          toolbar={{ searchColumn: 'customerName', searchPlaceholder: 'Search by customer…' }}
        />
      )}

      <CreatePolicySheet open={createOpen} onOpenChange={setCreateOpen} onSuccess={() => setCreateOpen(false)} />
    </div>
  );
}
