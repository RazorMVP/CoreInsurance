import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton, StatCard,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { validatedGet, ClaimDtoSchema, type ClaimDto } from '@cia/api-client';
import RegisterClaimSheet from './register/RegisterClaimSheet';
import SubmitClaimDialog  from './detail/SubmitClaimDialog';
import CancelClaimDialog  from './detail/CancelClaimDialog';

const statusVariant: Record<ClaimDto['status'], 'active' | 'pending' | 'draft' | 'rejected' | 'cancelled'> = {
  REGISTERED:          'draft',
  UNDER_INVESTIGATION: 'pending',
  RESERVED:            'pending',
  PENDING_APPROVAL:    'pending',
  APPROVED:            'active',
  REJECTED:            'rejected',
  SETTLED:             'active',
  WITHDRAWN:           'cancelled',
};

export default function ClaimsListPage() {
  const navigate = useNavigate();
  const [registerOpen,  setRegisterOpen]  = useState(false);
  const [submitTarget,  setSubmitTarget]  = useState<ClaimDto | null>(null);
  const [cancelTarget,  setCancelTarget]  = useState<ClaimDto | null>(null);

  const claimsQuery = useQuery<ClaimDto[]>({
    queryKey: ['claims'],
    queryFn: () => validatedGet('/api/v1/claims', z.array(ClaimDtoSchema)),
  });
  const claims = claimsQuery.data ?? [];

  // Dashboard stats. "Paid" is approximated by approvedAmount — the actual
  // paid status is tracked via credit-note + payment chain in cia-finance,
  // but approvedAmount is what's been authorised for payment.
  const open      = claims.filter(c => !['SETTLED', 'WITHDRAWN'].includes(c.status)).length;
  const reserved  = claims.reduce((s, c) => s + c.reserveAmount, 0);
  const approved  = claims.reduce((s, c) => s + (c.approvedAmount ?? 0), 0);

  const columns: ColumnDef<ClaimDto>[] = [
    {
      accessorKey: 'claimNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Claim No." />,
      cell: ({ row }) => (
        <button
          className="font-mono text-xs text-primary hover:underline"
          onClick={() => navigate(`/claims/${row.original.id}`)}
        >
          {row.original.claimNumber}
        </button>
      ),
    },
    {
      accessorKey: 'policyNumber',
      header: 'Policy',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'customerName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Customer" />,
      cell: ({ getValue }) => <span className="text-sm font-medium">{getValue() as string}</span>,
    },
    {
      accessorKey: 'description',
      header: 'Description',
      cell: ({ getValue }) => {
        const d = getValue() as string;
        return <span className="text-sm text-muted-foreground">{d.length > 45 ? d.slice(0, 45) + '…' : d}</span>;
      },
    },
    {
      accessorKey: 'reserveAmount',
      header: 'Reserve',
      cell: ({ getValue }) => (
        <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>
      ),
    },
    {
      accessorKey: 'approvedAmount',
      header: 'Approved',
      cell: ({ getValue }) => {
        const v = (getValue() as number | null | undefined) ?? 0;
        return <span className={`text-sm tabular-nums ${v > 0 ? 'font-medium text-primary' : 'text-muted-foreground'}`}>
          {v > 0 ? `₦${v.toLocaleString()}` : '—'}
        </span>;
      },
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const s = getValue() as ClaimDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase().replace('_', ' ')}</Badge>;
      },
    },
    {
      accessorKey: 'incidentDate',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Incident" />,
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
              { label: 'View claim',          onClick: () => navigate(`/claims/${id}`) },
              ...(status === 'REGISTERED'                            ? [{ label: 'Start investigation', onClick: () => {} }] : []),
              ...(status === 'UNDER_INVESTIGATION' || status === 'RESERVED' ? [{ label: 'Submit for approval', onClick: () => setSubmitTarget(row.original) }] : []),
              ...(status === 'PENDING_APPROVAL'                      ? [{ label: 'Approve', onClick: () => {} }, { label: 'Reject', onClick: () => {}, className: 'text-destructive' }] : []),
              ...(status === 'APPROVED'                              ? [{ label: 'Generate DV', onClick: () => {} }] : []),
              ...(status !== 'SETTLED' && status !== 'WITHDRAWN' && status !== 'REJECTED'
                ? [{ label: 'Cancel claim', onClick: () => setCancelTarget(row.original), separator: true, className: 'text-destructive' }]
                : []),
            ]}
          />
        );
      },
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Claims"
        description="Manage the full claims lifecycle from notification through settlement."
        actions={
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => navigate('/claims/bulk')}>Bulk Register</Button>
            <Button onClick={() => setRegisterOpen(true)}>Register Claim</Button>
          </div>
        }
      />

      {/* Dashboard summary */}
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Open Claims"      value={String(open)} sub={`${claims.length} total`} />
        <StatCard label="Total Reserve"    value={`₦${reserved.toLocaleString()}`} sub="Outstanding reserve" />
        <StatCard label="Total Approved (YTD)" value={`₦${approved.toLocaleString()}`} sub="Year to date" />
      </div>

      {claimsQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : claims.length === 0 ? (
        <EmptyState
          title="No claims yet"
          description="Register a claim notification to start the claims process."
          action={<Button onClick={() => setRegisterOpen(true)}>Register Claim</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={claims}
          toolbar={{ searchColumn: 'customerName', searchPlaceholder: 'Search by customer…' }}
        />
      )}

      <RegisterClaimSheet
        open={registerOpen}
        onOpenChange={setRegisterOpen}
        onSuccess={() => setRegisterOpen(false)}
      />

      <SubmitClaimDialog
        open={submitTarget !== null}
        onOpenChange={(v) => { if (!v) setSubmitTarget(null); }}
        claim={submitTarget}
        onConfirm={() => setSubmitTarget(null)}
      />

      <CancelClaimDialog
        open={cancelTarget !== null}
        onOpenChange={(v) => { if (!v) setCancelTarget(null); }}
        claim={cancelTarget}
        onConfirm={() => setCancelTarget(null)}
      />
    </div>
  );
}
