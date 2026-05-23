import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, ConfirmDeleteDialog, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader, Skeleton, toast,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, customerLabel, type CustomerDto } from '@cia/api-client';
import IndividualOnboardingSheet from './individual/IndividualOnboardingSheet';
import CorporateOnboardingSheet from './corporate/CorporateOnboardingSheet';

const kycVariant: Record<CustomerDto['kycStatus'], 'active' | 'pending' | 'rejected'> = { VERIFIED: 'active', PENDING: 'pending', FAILED: 'rejected', RESUBMIT: 'pending' };
const statusVariant: Record<CustomerDto['customerStatus'], 'active' | 'draft' | 'rejected'> = { ACTIVE: 'active', INACTIVE: 'draft', BLACKLISTED: 'rejected' };

export default function CustomersListPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [indivOpen, setIndivOpen] = useState(false);
  const [corpOpen,  setCorpOpen]  = useState(false);
  // Blacklist confirmation — POST /api/v1/customers/{id}/blacklist with a
  // mandatory reason. Re-uses ConfirmDeleteDialog because the reason-required
  // shape is identical; the action is destructive even though it's not a delete.
  const [blacklistTarget, setBlacklistTarget] = useState<CustomerDto | null>(null);

  const customersQuery = useQuery<CustomerDto[]>({
    queryKey: ['customers'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CustomerDto[] }>('/api/v1/customers');
      return res.data.data;
    },
  });
  const customers = customersQuery.data ?? [];

  const blacklist = useMutation({
    mutationFn: async ({ id, reason }: { id: string; reason: string }) => {
      await apiClient.post(`/api/v1/customers/${id}/blacklist`, { reason });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      toast({ title: 'Customer blacklisted' });
      setBlacklistTarget(null);
    },
    onError: () => {
      toast({ variant: 'destructive', title: 'Could not blacklist customer' });
    },
  });

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
      cell: ({ row }) => {
        const isBlacklisted = row.original.customerStatus === 'BLACKLISTED';
        return (
          <DataTableRowActions row={row} actions={[
            { label: 'View details', onClick: (r) => navigate(`/customers/${r.original.id}`) },
            // Update KYC lives on the customer detail page (EditCustomerSheet
            // handles the reason-required KYC update flow). The list-row entry
            // is a shortcut that drops the user there.
            { label: 'Update KYC',   onClick: (r) => navigate(`/customers/${r.original.id}`) },
            ...(isBlacklisted ? [] : [{
              label: 'Blacklist',
              onClick: (r: { original: CustomerDto }) => setBlacklistTarget(r.original),
              separator: true,
              className: 'text-destructive',
            }]),
          ]} />
        );
      },
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
      <ConfirmDeleteDialog
        open={blacklistTarget !== null}
        onOpenChange={(v) => { if (!v) setBlacklistTarget(null); }}
        entityLabel="Blacklist customer"
        entityName={blacklistTarget ? customerLabel(blacklistTarget) : undefined}
        busy={blacklist.isPending}
        onConfirm={(reason) => { if (blacklistTarget) blacklist.mutate({ id: blacklistTarget.id, reason }); }}
      />
    </div>
  );
}
