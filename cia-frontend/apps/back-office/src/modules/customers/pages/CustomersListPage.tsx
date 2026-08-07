import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, ConfirmDeleteDialog, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, toast,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, validatedList, CustomerSummaryDtoSchema, type CustomerSummaryDto } from '@cia/api-client';
import { useServerPagination } from '@/lib/use-server-pagination';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import IndividualOnboardingSheet from './individual/IndividualOnboardingSheet';
import CorporateOnboardingSheet from './corporate/CorporateOnboardingSheet';

const kycVariant: Record<CustomerSummaryDto['kycStatus'], 'active' | 'pending' | 'rejected'> = { VERIFIED: 'active', PENDING: 'pending', FAILED: 'rejected', RESUBMIT: 'pending' };
const statusVariant: Record<CustomerSummaryDto['customerStatus'], 'active' | 'draft' | 'rejected'> = { ACTIVE: 'active', INACTIVE: 'draft', BLACKLISTED: 'rejected' };
const CUSTOMER_STATUSES = Object.keys(statusVariant) as CustomerSummaryDto['customerStatus'][];

export default function CustomersListPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [indivOpen, setIndivOpen] = useState(false);
  const [corpOpen,  setCorpOpen]  = useState(false);
  // Blacklist confirmation — POST /api/v1/customers/{id}/blacklist with a
  // mandatory reason. Re-uses ConfirmDeleteDialog because the reason-required
  // shape is identical; the action is destructive even though it's not a delete.
  const [blacklistTarget, setBlacklistTarget] = useState<CustomerSummaryDto | null>(null);

  const { page, size, sort, filters, setPage, setSize, setSort, setFilter } =
    useServerPagination({ defaultSize: 20, defaultSort: 'createdAt,desc' });
  const status = filters.status ?? '';
  const [searchInput, setSearchInput] = useState(filters.q ?? '');
  const debouncedSearch = useDebouncedValue(searchInput, 300);
  useEffect(() => { setFilter('q', debouncedSearch); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [debouncedSearch]);

  const customersQuery = useQuery({
    queryKey: ['customers', page, size, sort, status, filters.q ?? ''],
    queryFn: () => validatedList('/api/v1/customers', CustomerSummaryDtoSchema, {
      params: { page, size, sort, ...(status ? { status } : {}), ...(filters.q ? { q: filters.q } : {}) },
    }),
  });
  const customers = customersQuery.data?.data ?? [];
  const total     = customersQuery.data?.meta.total ?? 0;

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

  const columns: ColumnDef<CustomerSummaryDto>[] = [
    {
      // Computed label (firstName+lastName or companyName) — no single backing
      // column, so server sort is disabled (plain header, not the sort control).
      id: 'name',
      enableSorting: false,
      accessorFn: (row) => row.displayName,
      header: 'Customer',
      cell: ({ row }) => (
        <button
          className="text-left hover:underline"
          onClick={() => navigate(`/customers/${row.original.id}`)}
        >
          <p className="font-medium text-foreground">{row.original.displayName}</p>
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
      cell: ({ getValue }) => { const s = getValue() as CustomerSummaryDto['kycStatus']; return <Badge variant={kycVariant[s]}>{s.toLowerCase()}</Badge>; },
    },
    {
      accessorKey: 'customerStatus',
      header: 'Status',
      cell: ({ getValue }) => { const s = getValue() as CustomerSummaryDto['customerStatus']; return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>; },
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
              onClick: (r: { original: CustomerSummaryDto }) => setBlacklistTarget(r.original),
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
          <div className="flex items-center gap-2">
            <Select value={status || 'ALL'} onValueChange={(v) => setFilter('status', v === 'ALL' ? '' : v)}>
              <SelectTrigger className="w-40"><SelectValue placeholder="Status" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All statuses</SelectItem>
                {CUSTOMER_STATUSES.map((s) => (
                  <SelectItem key={s} value={s}>{s.toLowerCase()}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button>New Customer ▾</Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onClick={() => setIndivOpen(true)}>Individual customer</DropdownMenuItem>
                <DropdownMenuItem onClick={() => setCorpOpen(true)}>Corporate customer</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        }
      />
      {customersQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : total === 0 && !status && !filters.q ? (
        <EmptyState title="No customers yet" description="Onboard your first customer." action={<Button onClick={() => setIndivOpen(true)}>Onboard Customer</Button>} />
      ) : (
        <DataTable
          columns={columns}
          data={customers}
          toolbar={{ searchPlaceholder: 'Search customers…', searchValue: searchInput, onSearchChange: setSearchInput }}
          serverPagination={{ page, size, total, onPageChange: setPage, onSizeChange: setSize, sort, onSortChange: setSort }}
        />
      )}
      <IndividualOnboardingSheet open={indivOpen} onOpenChange={setIndivOpen} onSuccess={() => setIndivOpen(false)} />
      <CorporateOnboardingSheet  open={corpOpen}  onOpenChange={setCorpOpen}  onSuccess={() => setCorpOpen(false)}  />
      <ConfirmDeleteDialog
        open={blacklistTarget !== null}
        onOpenChange={(v) => { if (!v) setBlacklistTarget(null); }}
        entityLabel="Blacklist customer"
        entityName={blacklistTarget ? blacklistTarget.displayName : undefined}
        busy={blacklist.isPending}
        onConfirm={(reason) => { if (blacklistTarget) blacklist.mutate({ id: blacklistTarget.id, reason }); }}
      />
    </div>
  );
}
