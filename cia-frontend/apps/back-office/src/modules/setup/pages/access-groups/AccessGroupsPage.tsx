import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { AccessGroupDto } from '@cia/api-client';
import AccessGroupSheet from './AccessGroupSheet';

const mockGroups: AccessGroupDto[] = [
  { id: 'ag1', name: 'System Admin',   permissions: ['setup:create','setup:update','setup:view'], userCount: 1 },
  { id: 'ag2', name: 'Underwriter',    permissions: ['underwriting:create','underwriting:view','underwriting:approve'], userCount: 3 },
  { id: 'ag3', name: 'Claims Officer', permissions: ['claims:create','claims:view','claims:approve'], userCount: 2 },
  { id: 'ag4', name: 'Finance Officer',permissions: ['finance:create','finance:view','finance:approve'], userCount: 1 },
  { id: 'ag5', name: 'System Auditor', permissions: ['audit:view'], userCount: 1 },
];

export default function AccessGroupsPage() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<AccessGroupDto | null>(null);

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
      accessorKey: 'userCount',
      header: 'Users',
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
        title="Access Groups"
        description="Define permission sets assigned to users. Each user belongs to one access group."
        actions={<Button onClick={openCreate}>Add Group</Button>}
      />
      {mockGroups.length === 0 ? (
        <EmptyState title="No access groups yet" action={<Button onClick={openCreate}>Add Group</Button>} />
      ) : (
        <DataTable
          columns={columns}
          data={mockGroups}
          toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search groups…' }}
        />
      )}
      <AccessGroupSheet open={sheetOpen} onOpenChange={setSheetOpen} group={editing} onSuccess={() => setSheetOpen(false)} />
    </div>
  );
}
