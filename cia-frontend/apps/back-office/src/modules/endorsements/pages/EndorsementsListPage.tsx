import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, ENDORSEMENT_TYPE_LABELS, type EndorsementDto } from '@cia/api-client';
import { formatNaira } from '@/lib/format';
import CreateEndorsementSheet from './create/CreateEndorsementSheet';

const statusVariant: Record<EndorsementDto['status'], 'active' | 'pending' | 'draft' | 'rejected'> = {
  APPROVED:  'active',
  SUBMITTED: 'pending',
  DRAFT:     'draft',
  REJECTED:  'rejected',
};

export default function EndorsementsListPage() {
  const navigate = useNavigate();
  const [createOpen, setCreateOpen] = useState(false);

  const endorsementsQuery = useQuery<EndorsementDto[]>({
    queryKey: ['endorsements'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: EndorsementDto[] }>('/api/v1/endorsements');
      return res.data.data;
    },
  });
  const endorsements = endorsementsQuery.data ?? [];

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
          {ENDORSEMENT_TYPE_LABELS[getValue() as EndorsementDto['endorsementType']]}
        </Badge>
      ),
    },
    {
      accessorKey: 'newSumInsured',
      header: 'New Sum Insured',
      cell: ({ getValue }) => (
        <span className="text-sm tabular-nums">{formatNaira(getValue() as number | null | undefined)}</span>
      ),
    },
    {
      accessorKey: 'premiumAdjustment',
      header: 'Pro-rata Premium',
      cell: ({ getValue }) => {
        const v = getValue() as number | null | undefined;
        if (v == null) return <span className="text-sm tabular-nums">—</span>;
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
      accessorKey: 'effectiveDate',
      header: 'Effective Date',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const { status, id } = row.original;
        // All workflow actions live on the detail page — list rows route there.
        const goDetail = () => navigate(`/endorsements/${id}`);
        return (
          <DataTableRowActions
            row={row}
            actions={[
              { label: 'View details',      onClick: goDetail },
              ...(status === 'DRAFT'     ? [{ label: 'Submit for approval', onClick: goDetail }] : []),
              ...(status === 'SUBMITTED' ? [{ label: 'Approve',             onClick: goDetail }, { label: 'Reject', onClick: goDetail, className: 'text-destructive' }] : []),
              ...(status === 'APPROVED'  ? [{ label: 'Download document',   onClick: goDetail }] : []),
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

      {endorsementsQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : endorsements.length === 0 ? (
        <EmptyState
          title="No endorsements yet"
          description="Create an endorsement to amend an existing policy."
          action={<Button onClick={() => setCreateOpen(true)}>New Endorsement</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={endorsements}
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
