import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton, toast,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { apiClient, validatedGet, ProductDtoSchema, type ProductDto } from '@cia/api-client';
import ProductSheet from './ProductSheet';
import CommissionSetupsSheet from './CommissionSetupsSheet';

export default function ProductsPage() {
  const queryClient = useQueryClient();
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<ProductDto | null>(null);
  const [commissionsFor, setCommissionsFor] = useState<ProductDto | null>(null);

  const productsQuery = useQuery<ProductDto[]>({
    queryKey: ['setup', 'products'],
    queryFn: () => validatedGet('/api/v1/setup/products', z.array(ProductDtoSchema)),
  });
  const products = productsQuery.data ?? [];

  // Activate/Deactivate — backend has no dedicated PATCH route, so we re-send
  // the full ProductRequest with `active` flipped. ProductDto and
  // ProductRequest share the editable shape (everything minus id +
  // classOfBusinessName which is a server-side denormalisation).
  const toggleActive = useMutation({
    mutationFn: async (p: ProductDto) => {
      const body = {
        name:              p.name,
        code:              p.code,
        classOfBusinessId: p.classOfBusinessId,
        type:              p.type,
        rate:              p.rate,
        minPremium:        p.minPremium,
        active:            !p.active,
        sections:          p.sections ?? [],
      };
      await apiClient.put(`/api/v1/setup/products/${p.id}`, body);
    },
    onSuccess: (_, p) => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'products'] });
      toast({ title: p.active ? 'Product deactivated' : 'Product activated' });
    },
    onError: () => {
      toast({ variant: 'destructive', title: 'Could not toggle product status' });
    },
  });

  function openCreate() { setEditing(null); setSheetOpen(true); }
  function openEdit(p: ProductDto) { setEditing(p); setSheetOpen(true); }

  const columns: ColumnDef<ProductDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Product" />,
      cell: ({ row }) => (
        <div>
          <p className="font-medium text-foreground">{row.original.name}</p>
          <p className="font-mono text-xs text-muted-foreground">{row.original.code}</p>
        </div>
      ),
    },
    {
      accessorKey: 'classOfBusinessName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Class" />,
      cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span>,
    },
    {
      accessorKey: 'type',
      header: 'Type',
      cell: ({ getValue }) => {
        const t = getValue() as string;
        return <Badge variant="outline" className="text-xs">{t === 'SINGLE_RISK' ? 'Single Risk' : 'Multi Risk'}</Badge>;
      },
    },
    {
      accessorKey: 'rate',
      header: 'Premium Rate',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as number}%</span>,
    },
    {
      accessorKey: 'active',
      header: 'Status',
      cell: ({ getValue }) => {
        const active = getValue() as boolean;
        return <Badge variant={active ? 'active' : 'draft'}>{active ? 'active' : 'inactive'}</Badge>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            { label: 'Edit',               onClick: (r) => openEdit(r.original) },
            { label: 'Manage Commissions', onClick: (r) => setCommissionsFor(r.original) },
            { label: row.original.active ? 'Deactivate' : 'Activate', onClick: (r) => toggleActive.mutate(r.original) },
          ]}
        />
      ),
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Products"
        description="Manage insurance products, their risk types, and commission rates."
        actions={<Button onClick={openCreate}>Add Product</Button>}
      />
      {productsQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : products.length === 0 ? (
        <EmptyState title="No products yet" action={<Button onClick={openCreate}>Add Product</Button>} />
      ) : (
        <DataTable columns={columns} data={products}
          toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search products…' }} />
      )}
      <ProductSheet open={sheetOpen} onOpenChange={setSheetOpen} product={editing} onSuccess={() => setSheetOpen(false)} />
      <CommissionSetupsSheet
        open={!!commissionsFor}
        onOpenChange={(v) => { if (!v) setCommissionsFor(null); }}
        product={commissionsFor}
      />
    </div>
  );
}
