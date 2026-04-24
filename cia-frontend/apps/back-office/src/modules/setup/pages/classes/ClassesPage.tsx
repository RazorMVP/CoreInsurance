import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { ClassOfBusinessDto } from '@cia/api-client';
import ClassSheet from './ClassSheet';

const mockClasses: ClassOfBusinessDto[] = [
  { id: '1', name: 'Motor (Private)',  code: 'MTR-PRV', products: 2 },
  { id: '2', name: 'Motor (Commercial)',code: 'MTR-COM', products: 1 },
  { id: '3', name: 'Fire & Burglary',  code: 'F&B',     products: 3 },
  { id: '4', name: 'Marine Cargo',     code: 'MCG',     products: 1 },
  { id: '5', name: 'Life (Group)',      code: 'LIFE-GRP',products: 0 },
];

export default function ClassesPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<ClassOfBusinessDto | null>(null);

  function openCreate() { setEditing(null); setSheetOpen(true); }
  function openEdit(c: ClassOfBusinessDto) { setEditing(c); setSheetOpen(true); }

  const columns: ColumnDef<ClassOfBusinessDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Class Name" />,
      cell: ({ getValue }) => <span className="font-medium">{getValue() as string}</span>,
    },
    {
      accessorKey: 'code',
      header: 'Code',
      cell: ({ getValue }) => (
        <Badge variant="outline" className="font-mono text-xs">{getValue() as string}</Badge>
      ),
    },
    {
      accessorKey: 'products',
      header: 'Products',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as number}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            { label: 'Edit',   onClick: (r) => openEdit(r.original) },
            { label: 'Delete', onClick: () => {}, separator: true, className: 'text-destructive' },
          ]}
        />
      ),
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Classes of Business"
        description="Define the insurance classes that products are grouped under."
        actions={<Button onClick={openCreate}>Add Class</Button>}
      />
      {mockClasses.length === 0 ? (
        <EmptyState title="No classes yet" action={<Button onClick={openCreate}>Add Class</Button>} />
      ) : (
        <DataTable columns={columns} data={mockClasses}
          toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search classes…' }} />
      )}
      <ClassSheet open={sheetOpen} onOpenChange={setSheetOpen} cls={editing} onSuccess={() => setSheetOpen(false)} />
    </div>
  );
}
