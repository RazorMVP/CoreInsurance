import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, StatCard,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { ClaimDto } from '@cia/api-client';
import RegisterClaimSheet from './register/RegisterClaimSheet';
import SubmitClaimDialog  from './detail/SubmitClaimDialog';
import CancelClaimDialog  from './detail/CancelClaimDialog';

const mockClaims: ClaimDto[] = [
  { id: 'cl1', claimNumber: 'CLM-2026-00001', policyId: 'pol1', policyNumber: 'POL-2026-00001', customerId: 'c1', customerName: 'Chioma Okafor',     status: 'PROCESSING',        incidentDate: '2026-03-10', registeredDate: '2026-03-12', description: 'Rear-end collision at Ozumba Mbadiwe Ave.', estimatedLoss: 850_000,  reserveAmount: 650_000, paidAmount: 0,       surveyorId: 'sv1', surveyorName: 'Maxwell & Partners', createdAt: '2026-03-12', updatedAt: '2026-03-15' },
  { id: 'cl2', claimNumber: 'CLM-2026-00002', policyId: 'pol5', policyNumber: 'POL-2026-00004', customerId: 'c2', customerName: 'Alaba Trading Co.', status: 'PENDING_APPROVAL',  incidentDate: '2026-03-18', registeredDate: '2026-03-20', description: 'Water damage to goods in transit — Apapa port.',    estimatedLoss: 1_200_000, reserveAmount: 950_000, paidAmount: 0,       surveyorId: undefined,   surveyorName: undefined,              createdAt: '2026-03-20', updatedAt: '2026-03-22' },
  { id: 'cl3', claimNumber: 'CLM-2026-00003', policyId: 'pol1', policyNumber: 'POL-2026-00001', customerId: 'c1', customerName: 'Chioma Okafor',     status: 'REGISTERED',        incidentDate: '2026-03-28', registeredDate: '2026-03-29', description: 'Windscreen cracked by flying debris.',           estimatedLoss: 85_000,   reserveAmount: 75_000,  paidAmount: 0,       surveyorId: undefined,   surveyorName: undefined,              createdAt: '2026-03-29', updatedAt: '2026-03-29' },
  { id: 'cl4', claimNumber: 'CLM-2025-00088', policyId: 'pol4', policyNumber: 'POL-2025-00088', customerId: 'c5', customerName: 'Ngozi Adeyemi',     status: 'SETTLED',           incidentDate: '2025-10-05', registeredDate: '2025-10-08', description: 'Fire damage to contents — store room B.',        estimatedLoss: 320_000,  reserveAmount: 280_000, paidAmount: 265_000, surveyorId: 'sv2', surveyorName: 'Lagos Assessors Ltd',  createdAt: '2025-10-08', updatedAt: '2025-11-02' },
  { id: 'cl5', claimNumber: 'CLM-2026-00004', policyId: 'pol2', policyNumber: 'POL-2026-00002', customerId: 'c2', customerName: 'Alaba Trading Co.', status: 'APPROVED',          incidentDate: '2026-02-20', registeredDate: '2026-02-22', description: 'Burglary — electronic equipment stolen.',       estimatedLoss: 450_000,  reserveAmount: 420_000, paidAmount: 0,       surveyorId: 'sv1', surveyorName: 'Maxwell & Partners', createdAt: '2026-02-22', updatedAt: '2026-03-05' },
];

const statusVariant: Record<ClaimDto['status'], 'active' | 'pending' | 'draft' | 'rejected' | 'cancelled'> = {
  REGISTERED:       'draft',
  PROCESSING:       'pending',
  PENDING_APPROVAL: 'pending',
  APPROVED:         'active',
  REJECTED:         'rejected',
  SETTLED:          'active',
  CLOSED:           'cancelled',
  WITHDRAWN:        'cancelled',
};

export default function ClaimsListPage() {
  const navigate = useNavigate();
  const [registerOpen,  setRegisterOpen]  = useState(false);
  const [submitTarget,  setSubmitTarget]  = useState<ClaimDto | null>(null);
  const [cancelTarget,  setCancelTarget]  = useState<ClaimDto | null>(null);

  // Dashboard stats
  const open      = mockClaims.filter(c => !['SETTLED','CLOSED','WITHDRAWN'].includes(c.status)).length;
  const reserved  = mockClaims.reduce((s, c) => s + c.reserveAmount, 0);
  const paid      = mockClaims.reduce((s, c) => s + c.paidAmount, 0);

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
      accessorKey: 'paidAmount',
      header: 'Paid',
      cell: ({ getValue }) => {
        const v = getValue() as number;
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
              ...(status === 'REGISTERED'     ? [{ label: 'Start processing',    onClick: () => {} }] : []),
              ...(status === 'PROCESSING'     ? [{ label: 'Submit for approval', onClick: () => setSubmitTarget(row.original) }] : []),
              ...(status === 'PENDING_APPROVAL' ? [{ label: 'Approve', onClick: () => {} }, { label: 'Reject', onClick: () => {}, className: 'text-destructive' }] : []),
              ...(status === 'APPROVED'       ? [{ label: 'Generate DV',         onClick: () => {} }] : []),
              ...(status !== 'SETTLED' && status !== 'CLOSED' ? [{ label: 'Cancel claim', onClick: () => setCancelTarget(row.original), separator: true, className: 'text-destructive' }] : []),
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
        <StatCard label="Open Claims"      value={String(open)} sub={`${mockClaims.length} total`} />
        <StatCard label="Total Reserve"    value={`₦${reserved.toLocaleString()}`} sub="Outstanding reserve" />
        <StatCard label="Total Paid (YTD)" value={`₦${paid.toLocaleString()}`} sub="Year to date" />
      </div>

      {mockClaims.length === 0 ? (
        <EmptyState
          title="No claims yet"
          description="Register a claim notification to start the claims process."
          action={<Button onClick={() => setRegisterOpen(true)}>Register Claim</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={mockClaims}
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
