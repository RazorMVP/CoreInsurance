import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { validatedGet, AccessGroupDtoSchema, type AccessGroupDto } from '@cia/api-client';
import { useDeleteWithReason } from '@/lib/use-delete-with-reason';
import AccessGroupSheet from './AccessGroupSheet';

export default function AccessGroupsPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<AccessGroupDto | null>(null);
  // Soft-delete with reason — same pattern as the other Setup pages. The
  // backend (AccessGroupController.delete) currently ignores the ?reason=
  // query param; the audit-trail completion is logged as backlog F1d.
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<AccessGroupDto>({
    endpoint: (id) => `/api/v1/setup/access-groups/${id}`,
    invalidateKey: ['setup', 'access-groups'],
    entityLabel: 'Access Group',
    entityName: (g) => g.name,
  });

  const groupsQuery = useQuery<AccessGroupDto[]>({
    queryKey: ['setup', 'access-groups'],
    queryFn: () => validatedGet('/api/v1/setup/access-groups', z.array(AccessGroupDtoSchema)),
  });
  const groups = groupsQuery.data ?? [];

  function openCreate() { setEditing(null); setSheetOpen(true); }
  function openEdit(g: AccessGroupDto) { setEditing(g); setSheetOpen(true); }

  const columns: ColumnDef<AccessGroupDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Group Name" />,
      cell: ({ getValue }) => <span className="font-medium">{getValue() as string}</span>,
    },
    {
      accessorKey: 'permissions',
      header: 'Permissions',
      cell: ({ getValue }) => {
        const perms = getValue() as string[];
        return (
          <div className="flex flex-wrap gap-1">
            {perms.slice(0, 3).map((p) => (
              <Badge key={p} variant="default" className="text-[10px]">{p}</Badge>
            ))}
            {perms.length > 3 && (
              <Badge variant="outline" className="text-[10px]">+{perms.length - 3} more</Badge>
            )}
          </div>
        );
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            { label: 'Edit',   onClick: (r) => openEdit(r.original) },
            { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
          ]}
        />
      ),
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Access Groups"
        description="Define permission sets assigned to users. Each user belongs to one access group."
        actions={<Button onClick={openCreate}>Add Group</Button>}
      />
      {groupsQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : groups.length === 0 ? (
        <EmptyState title="No access groups yet" action={<Button onClick={openCreate}>Add Group</Button>} />
      ) : (
        <DataTable
          columns={columns}
          data={groups}
          toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search groups…' }}
        />
      )}
      <AccessGroupSheet open={sheetOpen} onOpenChange={setSheetOpen} group={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </div>
  );
}
