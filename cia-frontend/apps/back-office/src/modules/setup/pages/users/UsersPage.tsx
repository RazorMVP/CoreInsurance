import { useState } from 'react';
import {
  Badge, Button, ConfirmDeleteDialog, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton, toast,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { apiClient, validatedGet, UserDtoSchema, type UserDto } from '@cia/api-client';
import UserSheet from './UserSheet';

const statusVariant: Record<UserDto['status'], 'active' | 'rejected' | 'draft'> = {
  ACTIVE:    'active',
  INACTIVE:  'draft',
  LOCKED:    'rejected',
};

export default function UsersPage() {
  const queryClient = useQueryClient();
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<UserDto | null>(null);
  // Deactivate confirmation — destructive enough to warrant the standard
  // ConfirmDeleteDialog UX (reason captured + recorded in audit log via the
  // backend's POST /deactivate handler).
  const [deactivateTarget, setDeactivateTarget] = useState<UserDto | null>(null);

  const usersQuery = useQuery<UserDto[]>({
    queryKey: ['setup', 'users'],
    queryFn: () => validatedGet('/api/v1/setup/users', z.array(UserDtoSchema)),
  });
  const users = usersQuery.data ?? [];

  // Send Keycloak's UPDATE_PASSWORD action email. No body needed — the
  // backend triggers Keycloak's own reset flow.
  const resetPassword = useMutation({
    mutationFn: async (id: string) => {
      await apiClient.post(`/api/v1/setup/users/${id}/reset-password`);
    },
    onSuccess: () => {
      toast({ title: 'Password-reset email sent' });
    },
    onError: () => {
      toast({ variant: 'destructive', title: 'Could not send reset email' });
    },
  });

  // Deactivate / re-activate the Keycloak user. The reason is captured for
  // audit but not required by the backend endpoint today (logged to local
  // audit only; Keycloak's own audit captures the enabled=false event).
  const deactivate = useMutation({
    mutationFn: async (id: string) => {
      await apiClient.post(`/api/v1/setup/users/${id}/deactivate`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'users'] });
      toast({ title: 'User deactivated' });
      setDeactivateTarget(null);
    },
    onError: () => {
      toast({ variant: 'destructive', title: 'Could not deactivate user' });
    },
  });

  const activate = useMutation({
    mutationFn: async (id: string) => {
      await apiClient.post(`/api/v1/setup/users/${id}/activate`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'users'] });
      toast({ title: 'User re-activated' });
    },
    onError: () => {
      toast({ variant: 'destructive', title: 'Could not re-activate user' });
    },
  });

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
      cell: ({ row }) => {
        const isActive = row.original.status === 'ACTIVE';
        return (
          <DataTableRowActions
            row={row}
            actions={[
              { label: 'Edit',           onClick: (r) => openEdit(r.original) },
              { label: 'Reset password', onClick: (r) => resetPassword.mutate(r.original.id) },
              isActive
                ? { label: 'Deactivate', onClick: (r: { original: UserDto }) => setDeactivateTarget(r.original), separator: true, className: 'text-destructive' }
                : { label: 'Activate',   onClick: (r: { original: UserDto }) => activate.mutate(r.original.id), separator: true },
            ]}
          />
        );
      },
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
      <ConfirmDeleteDialog
        open={deactivateTarget !== null}
        onOpenChange={(v) => { if (!v) setDeactivateTarget(null); }}
        entityLabel="Deactivate user"
        entityName={deactivateTarget ? `${deactivateTarget.firstName} ${deactivateTarget.lastName}` : undefined}
        busy={deactivate.isPending}
        onConfirm={() => { if (deactivateTarget) deactivate.mutate(deactivateTarget.id); }}
      />
    </div>
  );
}
