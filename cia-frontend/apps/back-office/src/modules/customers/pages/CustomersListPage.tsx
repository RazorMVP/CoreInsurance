import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { CustomerDto } from '@cia/api-client';
import IndividualOnboardingSheet from './individual/IndividualOnboardingSheet';
import CorporateOnboardingSheet from './corporate/CorporateOnboardingSheet';

const mockCustomers: (CustomerDto & { customerNumber?: string })[] = [
  { id: 'c1', customerNumber: 'CUST/2026/IND/00000001', customerType: 'INDIVIDUAL', displayName: 'Chioma Okafor',     email: 'chioma@email.ng',   phone: '+234 803 111 0001', kycStatus: 'VERIFIED', status: 'ACTIVE',   brokerId: undefined, brokerName: undefined,        createdAt: '2026-01-15', updatedAt: '2026-01-15' },
  { id: 'c2', customerNumber: 'CUST/2026/CORP/00000001', customerType: 'CORPORATE',  displayName: 'Alaba Trading Co.', email: 'info@alaba.ng',     phone: '+234 701 222 0002', kycStatus: 'VERIFIED', status: 'ACTIVE',   brokerId: 'b1',      brokerName: 'Leadway Brokers', createdAt: '2026-01-20', updatedAt: '2026-01-20' },
  { id: 'c3', customerNumber: 'CUST/2026/IND/00000002', customerType: 'INDIVIDUAL', displayName: 'Emeka Eze',          email: 'emeka@email.ng',    phone: '+234 805 333 0003', kycStatus: 'PENDING',  status: 'ACTIVE',   brokerId: undefined, brokerName: undefined,        createdAt: '2026-02-01', updatedAt: '2026-02-01' },
  { id: 'c4', customerNumber: 'CUST/2026/CORP/00000002', customerType: 'CORPORATE',  displayName: 'Danforth Logistics', email: 'ops@danforth.ng',  phone: '+234 809 444 0004', kycStatus: 'FAILED',   status: 'ACTIVE',   brokerId: undefined, brokerName: undefined,        createdAt: '2026-02-10', updatedAt: '2026-02-10' },
  { id: 'c5', customerNumber: 'CUST/2026/IND/00000003', customerType: 'INDIVIDUAL', displayName: 'Ngozi Adeyemi',      email: 'ngozi@email.ng',    phone: '+234 706 555 0005', kycStatus: 'VERIFIED', status: 'INACTIVE', brokerId: 'b2',      brokerName: 'Stanbic Brokers', createdAt: '2026-02-15', updatedAt: '2026-02-15' },
];

const kycVariant: Record<CustomerDto['kycStatus'], 'active' | 'pending' | 'rejected'> = { VERIFIED: 'active', PENDING: 'pending', FAILED: 'rejected', RESUBMIT: 'pending' };
const statusVariant: Record<CustomerDto['status'], 'active' | 'draft' | 'rejected'> = { ACTIVE: 'active', INACTIVE: 'draft', BLACKLISTED: 'rejected' };

export default function CustomersListPage() {
  const navigate = useNavigate();
  const [indivOpen, setIndivOpen] = useState(false);
  const [corpOpen,  setCorpOpen]  = useState(false);

  const columns: ColumnDef<CustomerDto & { customerNumber?: string }>[] = [
    {
      accessorKey: 'displayName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Customer" />,
      cell: ({ row }) => (
        <button
          className="text-left hover:underline"
          onClick={() => navigate(`/customers/${row.original.id}`)}
        >
          <p className="font-medium text-foreground">{row.original.displayName}</p>
          <p className="text-xs text-muted-foreground font-mono">{row.original.customerNumber ?? row.original.email}</p>
        </button>
      ),
    },
    {
      accessorKey: 'customerType',
      header: 'Type',
      cell: ({ getValue }) => <Badge variant="outline" className="text-xs">{getValue() === 'INDIVIDUAL' ? 'Individual' : 'Corporate'}</Badge>,
    },
    {
      accessorKey: 'kycStatus',
      header: ({ column }) => <DataTableColumnHeader column={column} title="KYC" />,
      cell: ({ getValue }) => { const s = getValue() as CustomerDto['kycStatus']; return <Badge variant={kycVariant[s]}>{s.toLowerCase()}</Badge>; },
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => { const s = getValue() as CustomerDto['status']; return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>; },
    },
    {
      accessorKey: 'brokerName',
      header: 'Broker',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{(getValue() as string) ?? '—'}</span>,
    },
    {
      accessorKey: 'createdAt',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Created" />,
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions row={row} actions={[
          { label: 'View details', onClick: (r) => navigate(`/customers/${r.original.id}`) },
          { label: 'Update KYC',   onClick: () => {} },
          { label: 'Blacklist',    onClick: () => {}, separator: true, className: 'text-destructive' },
        ]} />
      ),
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Customers"
        description="Manage individual and corporate customer records, KYC status and onboarding."
        actions={
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button>New Customer ▾</Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => setIndivOpen(true)}>Individual customer</DropdownMenuItem>
              <DropdownMenuItem onClick={() => setCorpOpen(true)}>Corporate customer</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        }
      />
      {mockCustomers.length === 0 ? (
        <EmptyState title="No customers yet" description="Onboard your first customer." action={<Button onClick={() => setIndivOpen(true)}>Onboard Customer</Button>} />
      ) : (
        <DataTable columns={columns} data={mockCustomers} toolbar={{ searchColumn: 'displayName', searchPlaceholder: 'Search customers…' }} />
      )}
      <IndividualOnboardingSheet open={indivOpen} onOpenChange={setIndivOpen} onSuccess={() => setIndivOpen(false)} />
      <CorporateOnboardingSheet  open={corpOpen}  onOpenChange={setCorpOpen}  onSuccess={() => setCorpOpen(false)}  />
    </div>
  );
}
