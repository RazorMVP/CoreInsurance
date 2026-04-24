import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { EndorsementDto } from '@cia/api-client';
import CreateEndorsementSheet from './create/CreateEndorsementSheet';

const mockEndorsements: EndorsementDto[] = [
  { id: 'end1', endorsementNumber: 'END-2026-00001', policyId: 'pol1', policyNumber: 'POL-2026-00001', status: 'APPROVED',  endorsementType: 'RENEWAL',    sumInsured: 3_500_000, premium: 78_750, startDate: '2027-02-01', endDate: '2028-02-01', createdAt: '2026-12-15', updatedAt: '2026-12-20' },
  { id: 'end2', endorsementNumber: 'END-2026-00002', policyId: 'pol1', policyNumber: 'POL-2026-00001', status: 'SUBMITTED', endorsementType: 'INCREASE_SI', sumInsured: 4_500_000, premium: 15_625, startDate: '2026-05-01', endDate: '2027-02-01', createdAt: '2026-04-28', updatedAt: '2026-04-29' },
  { id: 'end3', endorsementNumber: 'END-2026-00003', policyId: 'pol5', policyNumber: 'POL-2026-00004', status: 'DRAFT',     endorsementType: 'EXTENSION',  sumInsured: 8_000_000, premium: 12_500, startDate: '2027-01-15', endDate: '2027-04-15', createdAt: '2026-12-20', updatedAt: '2026-12-20' },
  { id: 'end4', endorsementNumber: 'END-2026-00004', policyId: 'pol4', policyNumber: 'POL-2025-00088', status: 'APPROVED',  endorsementType: 'CANCELLATION', sumInsured: 5_000_000, premium: -18_000, startDate: '2025-09-01', endDate: '2026-03-01', createdAt: '2025-08-28', updatedAt: '2025-09-01' },
  { id: 'end5', endorsementNumber: 'END-2026-00005', policyId: 'pol2', policyNumber: 'POL-2026-00002', status: 'REJECTED',  endorsementType: 'DECREASE_SI', sumInsured: 10_000_000, premium: -5_000, startDate: '2026-06-01', endDate: '2027-03-01', createdAt: '2026-05-20', updatedAt: '2026-05-25' },
];

const TYPE_LABELS: Record<EndorsementDto['endorsementType'], string> = {
  RENEWAL:       'Renewal',
  EXTENSION:     'Extension of Period',
  CANCELLATION:  'Cancellation',
  REVERSAL:      'Reversal',
  REDUCTION:     'Reduction in Period',
  CHANGE_PERIOD: 'Change in Period',
  INCREASE_SI:   'Increase Sum Insured',
  DECREASE_SI:   'Decrease Sum Insured',
  ADD_ITEMS:     'Add Insured Items',
  DELETE_ITEMS:  'Delete Insured Items',
};

const statusVariant: Record<EndorsementDto['status'], 'active' | 'pending' | 'draft' | 'rejected'> = {
  APPROVED:  'active',
  SUBMITTED: 'pending',
  DRAFT:     'draft',
  REJECTED:  'rejected',
};

export default function EndorsementsListPage() {
  const navigate = useNavigate();
  const [createOpen, setCreateOpen] = useState(false);

  const columns: ColumnDef<EndorsementDto>[] = [
    {
      accessorKey: 'endorsementNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Endorsement No." />,
      cell: ({ row }) => (
        <button
          className="font-mono text-xs text-primary hover:underline"
          onClick={() => navigate(`/endorsements/${row.original.id}`)}
        >
          {row.original.endorsementNumber}
        </button>
      ),
    },
    {
      accessorKey: 'policyNumber',
      header: 'Policy',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'endorsementType',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Type" />,
      cell: ({ getValue }) => (
        <Badge variant="outline" className="text-xs whitespace-nowrap">
          {TYPE_LABELS[getValue() as EndorsementDto['endorsementType']]}
        </Badge>
      ),
    },
    {
      accessorKey: 'sumInsured',
      header: 'New Sum Insured',
      cell: ({ getValue }) => (
        <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>
      ),
    },
    {
      accessorKey: 'premium',
      header: 'Pro-rata Premium',
      cell: ({ getValue }) => {
        const v = getValue() as number;
        return (
          <span className={`text-sm font-medium tabular-nums ${v < 0 ? 'text-destructive' : ''}`}>
            {v < 0 ? '−' : ''}₦{Math.abs(v).toLocaleString()}
          </span>
        );
      },
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const s = getValue() as EndorsementDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>;
      },
    },
    {
      accessorKey: 'startDate',
      header: 'Effective Date',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const { status, id } = row.original;
        return (
          <DataTableRowActions
            row={row}
            actions={[
              { label: 'View details',      onClick: () => navigate(`/endorsements/${id}`) },
              ...(status === 'DRAFT'     ? [{ label: 'Submit for approval', onClick: () => {} }] : []),
              ...(status === 'SUBMITTED' ? [{ label: 'Approve',             onClick: () => {} }, { label: 'Reject', onClick: () => {}, className: 'text-destructive' }] : []),
              ...(status === 'APPROVED'  ? [{ label: 'Download document',   onClick: () => {} }] : []),
            ]}
          />
        );
      },
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Endorsements"
        description="Manage policy amendments — renewals, extensions, cancellations and sum insured changes."
        actions={
          <div className="flex gap-2">
            <Button
              variant="outline"
              onClick={() => navigate('/endorsements/reports/debit-note-analysis')}
            >
              Debit Note Analysis
            </Button>
            <Button onClick={() => setCreateOpen(true)}>New Endorsement</Button>
          </div>
        }
      />

      {mockEndorsements.length === 0 ? (
        <EmptyState
          title="No endorsements yet"
          description="Create an endorsement to amend an existing policy."
          action={<Button onClick={() => setCreateOpen(true)}>New Endorsement</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={mockEndorsements}
          toolbar={{ searchColumn: 'policyNumber', searchPlaceholder: 'Search by policy…' }}
        />
      )}

      <CreateEndorsementSheet
        open={createOpen}
        onOpenChange={setCreateOpen}
        onSuccess={() => setCreateOpen(false)}
      />
    </div>
  );
}
