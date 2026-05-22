import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader, Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, customerLabel, type CustomerDto } from '@cia/api-client';
import IndividualOnboardingSheet from './individual/IndividualOnboardingSheet';
import CorporateOnboardingSheet from './corporate/CorporateOnboardingSheet';

const kycVariant: Record<CustomerDto['kycStatus'], 'active' | 'pending' | 'rejected'> = { VERIFIED: 'active', PENDING: 'pending', FAILED: 'rejected', RESUBMIT: 'pending' };
const statusVariant: Record<CustomerDto['customerStatus'], 'active' | 'draft' | 'rejected'> = { ACTIVE: 'active', INACTIVE: 'draft', BLACKLISTED: 'rejected' };

export default function CustomersListPage() {
  const navigate = useNavigate();
  const [indivOpen, setIndivOpen] = useState(false);
  const [corpOpen,  setCorpOpen]  = useState(false);

  const customersQuery = useQuery<CustomerDto[]>({
    queryKey: ['customers'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CustomerDto[] }>('/api/v1/customers');
      return res.data.data;
    },
  });
  const customers = customersQuery.data ?? [];

  const columns: ColumnDef<CustomerDto>[] = [
    {
      id: 'name',
      accessorFn: (row) => customerLabel(row),
      header: ({ column }) => <DataTableColumnHeader column={column} title="Customer" />,
      cell: ({ row }) => (
        <button
          className="text-left hover:underline"
          onClick={() => navigate(`/customers/${row.original.id}`)}
        >
          <p className="font-medium text-foreground">{customerLabel(row.original)}</p>
          <p className="text-xs text-muted-foreground font-mono">{row.original.customerNumber}</p>
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
      accessorKey: 'customerStatus',
      header: 'Status',
      cell: ({ getValue }) => { const s = getValue() as CustomerDto['customerStatus']; return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>; },
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
      {customersQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : customers.length === 0 ? (
        <EmptyState title="No customers yet" description="Onboard your first customer." action={<Button onClick={() => setIndivOpen(true)}>Onboard Customer</Button>} />
      ) : (
        <DataTable columns={columns} data={customers} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search customers…' }} />
      )}
      <IndividualOnboardingSheet open={indivOpen} onOpenChange={setIndivOpen} onSuccess={() => setIndivOpen(false)} />
      <CorporateOnboardingSheet  open={corpOpen}  onOpenChange={setCorpOpen}  onSuccess={() => setCorpOpen(false)}  />
    </div>
  );
}
