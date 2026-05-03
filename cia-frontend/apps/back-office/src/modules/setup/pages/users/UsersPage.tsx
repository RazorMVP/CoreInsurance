import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type UserDto } from '@cia/api-client';
import UserSheet from './UserSheet';

const statusVariant: Record<UserDto['status'], 'active' | 'rejected' | 'draft'> = {
  ACTIVE:    'active',
  INACTIVE:  'draft',
  LOCKED:    'rejected',
};

export default function UsersPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<UserDto | null>(null);

  const usersQuery = useQuery<UserDto[]>({
    queryKey: ['setup', 'users'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: UserDto[] }>('/api/v1/setup/users');
      return res.data.data;
    },
  });
  const users = usersQuery.data ?? [];

  function openCreate() { setEditing(null); setSheetOpen(true); }
  function openEdit(u: UserDto) { setEditing(u); setSheetOpen(true); }

  const columns: ColumnDef<UserDto>[] = [
    {
      accessorKey: 'firstName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Name" />,
      cell: ({ row }) => (
        <div>
          <p className="font-medium text-foreground">{row.original.firstName} {row.original.lastName}</p>
          <p className="text-xs text-muted-foreground">{row.original.email}</p>
        </div>
      ),
    },
    {
      accessorKey: 'accessGroupName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Access Group" />,
      cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span>,
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const s = getValue() as UserDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>;
      },
    },
    {
      accessorKey: 'createdAt',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Created" />,
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            { label: 'Edit',           onClick: (r) => openEdit(r.original) },
            { label: 'Reset password', onClick: () => {} },
            { label: 'Deactivate',     onClick: () => {}, separator: true, className: 'text-destructive' },
          ]}
        />
      ),
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Users"
        description="Manage system users and their access group assignments."
        actions={<Button onClick={openCreate}>Add User</Button>}
      />

      {usersQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : users.length === 0 ? (
        <EmptyState
          title="No users yet"
          description="Add the first user to get started."
          action={<Button onClick={openCreate}>Add User</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={users}
          toolbar={{ searchColumn: 'firstName', searchPlaceholder: 'Search users…' }}
        />
      )}

      <UserSheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        user={editing}
        onSuccess={() => setSheetOpen(false)}
      />
    </div>
  );
}
