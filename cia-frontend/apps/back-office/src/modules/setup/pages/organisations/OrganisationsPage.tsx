import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader, Skeleton, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type BrokerDto } from '@cia/api-client';
import BrokerSheet from './BrokerSheet';

const statusVariant: Record<BrokerDto['status'], 'active' | 'draft'> = { ACTIVE: 'active', INACTIVE: 'draft' };

function BrokersTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing,   setEditing]   = useState<BrokerDto | null>(null);

  const brokersQuery = useQuery<BrokerDto[]>({
    queryKey: ['setup', 'brokers'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: BrokerDto[] }>('/api/v1/setup/brokers');
      return res.data.data;
    },
  });
  const brokers = brokersQuery.data ?? [];

  const columns: ColumnDef<BrokerDto>[] = [
    {
      accessorKey: 'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Broker" />,
      cell: ({ row }) => (
        <div>
          <p className="font-medium">{row.original.name}</p>
          <p className="font-mono text-xs text-muted-foreground">{row.original.code}</p>
        </div>
      ),
    },
    { accessorKey: 'contactPerson', header: 'Contact Person', cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span> },
    { accessorKey: 'email',         header: 'Email',          cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span> },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as BrokerDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>;
      },
    },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit',   onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: () => {}, separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Broker</Button>
      </div>
      {brokersQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : brokers.length === 0 ? (
        <EmptyState title="No brokers yet" />
      ) : (
        <DataTable columns={columns} data={brokers}
          toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search brokers…' }} />
      )}
      <BrokerSheet open={sheetOpen} onOpenChange={setSheetOpen} broker={editing} onSuccess={() => setSheetOpen(false)} />
    </>
  );
}

function SimpleOrgTab({ label }: { label: string }) {
  return (
    <EmptyState
      title={`${label} management`}
      description={`${label} records will appear here. Add the first one to get started.`}
      action={<Button size="sm">Add {label}</Button>}
    />
  );
}

export default function OrganisationsPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Organisations"
        description="Manage brokers, reinsurers, insurers, branches, SBUs and other organisational entities."
      />
      <Tabs defaultValue="brokers">
        <TabsList className="mb-4">
          <TabsTrigger value="brokers">Brokers</TabsTrigger>
          <TabsTrigger value="reinsurers">Reinsurers</TabsTrigger>
          <TabsTrigger value="insurers">Insurers</TabsTrigger>
          <TabsTrigger value="branches">Branches</TabsTrigger>
          <TabsTrigger value="sbus">SBUs</TabsTrigger>
          <TabsTrigger value="surveyors">Surveyors</TabsTrigger>
        </TabsList>
        <TabsContent value="brokers"><BrokersTab /></TabsContent>
        <TabsContent value="reinsurers"><SimpleOrgTab label="Reinsurer" /></TabsContent>
        <TabsContent value="insurers"><SimpleOrgTab label="Insurance Company" /></TabsContent>
        <TabsContent value="branches"><SimpleOrgTab label="Branch" /></TabsContent>
        <TabsContent value="sbus"><SimpleOrgTab label="SBU" /></TabsContent>
        <TabsContent value="surveyors"><SimpleOrgTab label="Surveyor" /></TabsContent>
      </Tabs>
    </div>
  );
}
