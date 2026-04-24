import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { ProductDto } from '@cia/api-client';
import ProductSheet from './ProductSheet';

const mockProducts: ProductDto[] = [
  { id: '1', name: 'Private Motor Comprehensive', code: 'PMC-001', classOfBusinessId: '1', classOfBusinessName: 'Motor (Private)',  type: 'SINGLE_RISK', status: 'ACTIVE', commissionRate: 12.5, createdAt: '2026-01-10' },
  { id: '2', name: 'Commercial Vehicle',           code: 'CMV-001', classOfBusinessId: '2', classOfBusinessName: 'Motor (Commercial)',type: 'SINGLE_RISK', status: 'ACTIVE', commissionRate: 10.0, createdAt: '2026-01-12' },
  { id: '3', name: 'Fire & Burglary Standard',     code: 'FAB-001', classOfBusinessId: '3', classOfBusinessName: 'Fire & Burglary',  type: 'MULTI_RISK',  status: 'ACTIVE', commissionRate: 8.0,  createdAt: '2026-01-15' },
  { id: '4', name: 'Marine Cargo Open Cover',      code: 'MCG-001', classOfBusinessId: '4', classOfBusinessName: 'Marine Cargo',     type: 'MULTI_RISK',  status: 'INACTIVE',commissionRate: 7.5,  createdAt: '2026-02-01' },
];

const statusVariant: Record<ProductDto['status'], 'active' | 'draft'> = {
  ACTIVE: 'active', INACTIVE: 'draft',
};

export default function ProductsPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<ProductDto | null>(null);

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
      {mockProducts.length === 0 ? (
        <EmptyState title="No products yet" action={<Button onClick={openCreate}>Add Product</Button>} />
      ) : (
        <DataTable columns={columns} data={mockProducts}
          toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search products…' }} />
      )}
      <ProductSheet open={sheetOpen} onOpenChange={setSheetOpen} product={editing} onSuccess={() => setSheetOpen(false)} />
    </div>
  );
}
