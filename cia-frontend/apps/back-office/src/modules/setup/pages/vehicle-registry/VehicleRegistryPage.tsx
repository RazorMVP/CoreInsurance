import { useState } from 'react';
import {
  Button, DataTable, DataTableColumnHeader, DataTableRowActions, EmptyState,
  PageHeader, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { useDeleteWithReason } from '@/lib/use-delete-with-reason';
import {
  apiClient,
  type VehicleMakeDto, type VehicleTypeDto, type VehicleModelDto,
} from '@cia/api-client';
import VehicleMakeSheet from './VehicleMakeSheet';
import VehicleTypeSheet from './VehicleTypeSheet';
import VehicleModelSheet from './VehicleModelSheet';

// ── Makes ──────────────────────────────────────────────────────────────────

function MakesTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<VehicleMakeDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<VehicleMakeDto>({
    endpoint: (id) => `/api/v1/setup/vehicle-makes/${id}`,
    invalidateKey: ['setup', 'vehicle-makes'],
    entityLabel: 'Vehicle Make',
    entityName: (m) => m.name,
  });

  const query = useQuery<VehicleMakeDto[]>({
    queryKey: ['setup', 'vehicle-makes'],
    queryFn: async () => (await apiClient.get<{ data: VehicleMakeDto[] }>('/api/v1/setup/vehicle-makes')).data.data,
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<VehicleMakeDto>[] = [
    { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Make" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Make</Button>
      </div>
      {query.isLoading
        ? <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
        : rows.length === 0
        ? <EmptyState title="No vehicle makes yet" description="Add the makes used in motor underwriting." />
        : <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search makes…' }} />}
      <VehicleMakeSheet open={sheetOpen} onOpenChange={setSheetOpen} make={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Types ──────────────────────────────────────────────────────────────────

function TypesTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<VehicleTypeDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<VehicleTypeDto>({
    endpoint: (id) => `/api/v1/setup/vehicle-types/${id}`,
    invalidateKey: ['setup', 'vehicle-types'],
    entityLabel: 'Vehicle Type',
    entityName: (t) => t.name,
  });

  const query = useQuery<VehicleTypeDto[]>({
    queryKey: ['setup', 'vehicle-types'],
    queryFn: async () => (await apiClient.get<{ data: VehicleTypeDto[] }>('/api/v1/setup/vehicle-types')).data.data,
  });
  const rows = query.data ?? [];

  const columns: ColumnDef<VehicleTypeDto>[] = [
    { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Type" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Type</Button>
      </div>
      {query.isLoading
        ? <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
        : rows.length === 0
        ? <EmptyState title="No vehicle types yet" description="Add the body types used in motor underwriting." />
        : <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search types…' }} />}
      <VehicleTypeSheet open={sheetOpen} onOpenChange={setSheetOpen} type={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Models (nested under a selected make) ────────────────────────────────────

function ModelsTab() {
  const [makeId, setMakeId] = useState<string>('');
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<VehicleModelDto | null>(null);

  const makesQuery = useQuery<VehicleMakeDto[]>({
    queryKey: ['setup', 'vehicle-makes'],
    queryFn: async () => (await apiClient.get<{ data: VehicleMakeDto[] }>('/api/v1/setup/vehicle-makes')).data.data,
  });
  const makes = makesQuery.data ?? [];
  const makeName = makes.find((m) => m.id === makeId)?.name ?? '';

  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<VehicleModelDto>({
    endpoint: (id) => `/api/v1/setup/vehicle-makes/${makeId}/models/${id}`,
    invalidateKey: ['setup', 'vehicle-models', makeId],
    entityLabel: 'Vehicle Model',
    entityName: (m) => m.name,
  });

  const modelsQuery = useQuery<VehicleModelDto[]>({
    queryKey: ['setup', 'vehicle-models', makeId],
    enabled: !!makeId,
    queryFn: async () => (await apiClient.get<{ data: VehicleModelDto[] }>(`/api/v1/setup/vehicle-makes/${makeId}/models`)).data.data,
  });
  const rows = modelsQuery.data ?? [];

  const columns: ColumnDef<VehicleModelDto>[] = [
    { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Model" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex items-center justify-between mb-3 gap-3">
        <Select value={makeId} onValueChange={(v) => setMakeId(v)}>
          <SelectTrigger className="w-64"><SelectValue placeholder="Select a make…" /></SelectTrigger>
          <SelectContent>{makes.map((m) => <SelectItem key={m.id} value={m.id}>{m.name}</SelectItem>)}</SelectContent>
        </Select>
        <Button size="sm" disabled={!makeId} onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Model</Button>
      </div>
      {!makeId
        ? <EmptyState title="Select a make" description="Pick a vehicle make above to manage its models." />
        : modelsQuery.isLoading
        ? <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
        : rows.length === 0
        ? <EmptyState title={`No models for ${makeName}`} description="Add the first model." />
        : <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search models…' }} />}
      {makeId && <VehicleModelSheet open={sheetOpen} onOpenChange={setSheetOpen} makeId={makeId} makeName={makeName} model={editing} onSuccess={() => setSheetOpen(false)} />}
      {deleteDialog}
    </>
  );
}

// ── Page ─────────────────────────────────────────────────────────────────────

export default function VehicleRegistryPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader title="Vehicle Registry" description="Manage vehicle makes, models and types used in motor class underwriting." />
      <Tabs defaultValue="makes">
        <TabsList>
          <TabsTrigger value="makes">Makes</TabsTrigger>
          <TabsTrigger value="models">Models</TabsTrigger>
          <TabsTrigger value="types">Types</TabsTrigger>
        </TabsList>
        <TabsContent value="makes" className="mt-4"><MakesTab /></TabsContent>
        <TabsContent value="models" className="mt-4"><ModelsTab /></TabsContent>
        <TabsContent value="types" className="mt-4"><TypesTab /></TabsContent>
      </Tabs>
    </div>
  );
}
