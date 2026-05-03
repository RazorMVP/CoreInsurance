import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type ProductDto } from '@cia/api-client';
import ProductSheet from './ProductSheet';

const statusVariant: Record<ProductDto['status'], 'active' | 'draft'> = {
  ACTIVE: 'active', INACTIVE: 'draft',
};

export default function ProductsPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<ProductDto | null>(null);

  const productsQuery = useQuery<ProductDto[]>({
    queryKey: ['setup', 'products'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ProductDto[] }>('/api/v1/setup/products');
      return res.data.data;
    },
  });
  const products = productsQuery.data ?? [];

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
      accessorKey: 'commissionRate',
      header: 'Commission',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as number}%</span>,
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as ProductDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            { label: 'Edit',   onClick: (r) => openEdit(r.original) },
            { label: row.original.status === 'ACTIVE' ? 'Deactivate' : 'Activate', onClick: () => {} },
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
    </div>
  );
}
