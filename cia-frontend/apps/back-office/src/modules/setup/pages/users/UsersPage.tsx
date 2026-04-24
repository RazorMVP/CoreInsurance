import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { UserDto } from '@cia/api-client';
import UserSheet from './UserSheet';

// Placeholder data — replace with useList('/api/v1/setup/users') when backend is up
const mockUsers: UserDto[] = [
  { id: '1', email: 'admin@nubsure.ng', firstName: 'Akinwale', lastName: 'Nubeero', status: 'ACTIVE',  accessGroupId: 'ag1', accessGroupName: 'System Admin',  createdAt: '2026-01-10' },
  { id: '2', email: 'uw@nubsure.ng',    firstName: 'Chidi',    lastName: 'Okafor',   status: 'ACTIVE',  accessGroupId: 'ag2', accessGroupName: 'Underwriter',   createdAt: '2026-01-15' },
  { id: '3', email: 'claims@nubsure.ng',firstName: 'Adaeze',   lastName: 'Nwosu',    status: 'ACTIVE',  accessGroupId: 'ag3', accessGroupName: 'Claims Officer', createdAt: '2026-02-01' },
  { id: '4', email: 'fin@nubsure.ng',   firstName: 'Emeka',    lastName: 'Obi',      status: 'INACTIVE',accessGroupId: 'ag4', accessGroupName: 'Finance Officer',createdAt: '2026-02-10' },
];

const statusVariant: Record<UserDto['status'], 'active' | 'rejected' | 'draft'> = {
  ACTIVE:    'active',
  INACTIVE:  'draft',
  LOCKED:    'rejected',
};

export default function UsersPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<UserDto | null>(null);

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

      {mockUsers.length === 0 ? (
        <EmptyState
          title="No users yet"
          description="Add the first user to get started."
          action={<Button onClick={openCreate}>Add User</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={mockUsers}
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
