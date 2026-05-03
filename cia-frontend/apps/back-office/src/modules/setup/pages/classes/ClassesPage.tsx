import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type ClassOfBusinessDto } from '@cia/api-client';
import ClassSheet from './ClassSheet';

export default function ClassesPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<ClassOfBusinessDto | null>(null);

  const classesQuery = useQuery<ClassOfBusinessDto[]>({
    queryKey: ['setup', 'classes-of-business'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ClassOfBusinessDto[] }>('/api/v1/setup/classes-of-business');
      return res.data.data;
    },
  });
  const classes = classesQuery.data ?? [];

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
      {classesQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : classes.length === 0 ? (
        <EmptyState title="No classes yet" action={<Button onClick={openCreate}>Add Class</Button>} />
      ) : (
        <DataTable columns={columns} data={classes}
          toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search classes…' }} />
      )}
      <ClassSheet open={sheetOpen} onOpenChange={setSheetOpen} cls={editing} onSuccess={() => setSheetOpen(false)} />
    </div>
  );
}
