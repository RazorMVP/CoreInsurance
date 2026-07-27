import { useEffect, useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions, EmptyState,
  Input, Label, PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, Tabs, TabsContent, TabsList, TabsTrigger, toast,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useDeleteWithReason } from '@/lib/use-delete-with-reason';
import {
  apiClient,
  type ClaimReserveCategoryDto, type NatureOfLossDto, type CauseOfLossDto,
  type ClaimNotificationTimelineDto, type ClaimDocumentRequirementDto, type ProductDto,
} from '@cia/api-client';
import ClaimReserveCategorySheet from './ClaimReserveCategorySheet';
import NatureOfLossSheet from './NatureOfLossSheet';
import CauseOfLossSheet from './CauseOfLossSheet';
import ClaimDocumentRequirementSheet from './ClaimDocumentRequirementSheet';

// ── Reserve Categories (shape A) ─────────────────────────────────────────────

function ReservesTab() {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<ClaimReserveCategoryDto | null>(null);
  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<ClaimReserveCategoryDto>({
    endpoint: (id) => `/api/v1/setup/claim-reserve-categories/${id}`,
    invalidateKey: ['setup', 'claim-reserve-categories'],
    entityLabel: 'Reserve Category',
    entityName: (c) => c.name,
  });
  const query = useQuery<ClaimReserveCategoryDto[]>({
    queryKey: ['setup', 'claim-reserve-categories'],
    queryFn: async () => (await apiClient.get<{ data: ClaimReserveCategoryDto[] }>('/api/v1/setup/claim-reserve-categories')).data.data,
  });
  const rows = query.data ?? [];
  const columns: ColumnDef<ClaimReserveCategoryDto>[] = [
    { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Name" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: 'code', header: ({ column }) => <DataTableColumnHeader column={column} title="Code" /> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];
  return (
    <>
      <div className="flex justify-end mb-3"><Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Category</Button></div>
      {query.isLoading
        ? <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
        : rows.length === 0
        ? <EmptyState title="No reserve categories yet" description="Add the categories used to bucket claim reserves." />
        : <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search categories…' }} />}
      <ClaimReserveCategorySheet open={sheetOpen} onOpenChange={setSheetOpen} category={editing} onSuccess={() => setSheetOpen(false)} />
      {deleteDialog}
    </>
  );
}

// ── Nature / Cause of Loss (shapes A + A′) ───────────────────────────────────

function LossTab() {
  const [natureSheetOpen, setNatureSheetOpen] = useState(false);
  const [editingNature, setEditingNature] = useState<NatureOfLossDto | null>(null);
  const [causeSheetOpen, setCauseSheetOpen] = useState(false);
  const [editingCause, setEditingCause] = useState<CauseOfLossDto | null>(null);

  const natureDelete = useDeleteWithReason<NatureOfLossDto>({
    endpoint: (id) => `/api/v1/setup/nature-of-loss/${id}`,
    invalidateKey: ['setup', 'nature-of-loss'], entityLabel: 'Nature of Loss', entityName: (n) => n.name,
  });
  const causeDelete = useDeleteWithReason<CauseOfLossDto>({
    endpoint: (id) => `/api/v1/setup/cause-of-loss/${id}`,
    invalidateKey: ['setup', 'cause-of-loss'], entityLabel: 'Cause of Loss', entityName: (c) => c.name,
  });

  const naturesQuery = useQuery<NatureOfLossDto[]>({
    queryKey: ['setup', 'nature-of-loss'],
    queryFn: async () => (await apiClient.get<{ data: NatureOfLossDto[] }>('/api/v1/setup/nature-of-loss')).data.data,
  });
  const causesQuery = useQuery<CauseOfLossDto[]>({
    queryKey: ['setup', 'cause-of-loss'],
    queryFn: async () => (await apiClient.get<{ data: CauseOfLossDto[] }>('/api/v1/setup/cause-of-loss')).data.data,
  });
  const natures = naturesQuery.data ?? [];
  const causes = causesQuery.data ?? [];

  const natureColumns: ColumnDef<NatureOfLossDto>[] = [
    { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Nature" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: 'code', header: ({ column }) => <DataTableColumnHeader column={column} title="Code" /> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditingNature(r.original); setNatureSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => natureDelete.setTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];
  const causeColumns: ColumnDef<CauseOfLossDto>[] = [
    { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Cause" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: 'code', header: ({ column }) => <DataTableColumnHeader column={column} title="Code" /> },
    { accessorKey: 'natureOfLossName', header: ({ column }) => <DataTableColumnHeader column={column} title="Nature" /> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditingCause(r.original); setCauseSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => causeDelete.setTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <div className="space-y-8">
      <PageSection title="Nature of Loss" actions={<Button size="sm" onClick={() => { setEditingNature(null); setNatureSheetOpen(true); }}>Add Nature</Button>}>
        {naturesQuery.isLoading
          ? <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
          : natures.length === 0
          ? <EmptyState title="No nature-of-loss types yet" description="Add high-level loss categories." />
          : <DataTable columns={natureColumns} data={natures} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search natures…' }} />}
      </PageSection>

      <PageSection title="Cause of Loss" actions={<Button size="sm" disabled={natures.length === 0} onClick={() => { setEditingCause(null); setCauseSheetOpen(true); }}>Add Cause</Button>}>
        {natures.length === 0
          ? <EmptyState title="Add a nature of loss first" description="Causes must be linked to a nature of loss." />
          : causesQuery.isLoading
          ? <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
          : causes.length === 0
          ? <EmptyState title="No cause-of-loss types yet" description="Add specific causes under a nature." />
          : <DataTable columns={causeColumns} data={causes} toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search causes…' }} />}
      </PageSection>

      <NatureOfLossSheet open={natureSheetOpen} onOpenChange={setNatureSheetOpen} nature={editingNature} onSuccess={() => setNatureSheetOpen(false)} />
      <CauseOfLossSheet open={causeSheetOpen} onOpenChange={setCauseSheetOpen} cause={editingCause} onSuccess={() => setCauseSheetOpen(false)} />
      {natureDelete.dialog}
      {causeDelete.dialog}
    </div>
  );
}

// ── Notification Timelines (shape B — per-product singleton) ──────────────────

function TimelinesTab() {
  const [productId, setProductId] = useState<string>('');
  const [days, setDays] = useState<string>('');
  const queryClient = useQueryClient();

  const productsQuery = useQuery<ProductDto[]>({
    queryKey: ['setup', 'products'],
    queryFn: async () => (await apiClient.get<{ data: ProductDto[] }>('/api/v1/setup/products')).data.data,
  });
  const products = productsQuery.data ?? [];

  const timelineQuery = useQuery<ClaimNotificationTimelineDto>({
    queryKey: ['setup', 'claim-notification-timeline', productId],
    enabled: !!productId,
    queryFn: async () => (await apiClient.get<{ data: ClaimNotificationTimelineDto }>(`/api/v1/setup/products/${productId}/claim-notification-timeline`)).data.data,
  });

  useEffect(() => {
    if (timelineQuery.data) setDays(String(timelineQuery.data.notificationDays));
  }, [timelineQuery.data]);

  const save = useMutation({
    mutationFn: async () => {
      const res = await apiClient.put<{ data: ClaimNotificationTimelineDto }>(
        `/api/v1/setup/products/${productId}/claim-notification-timeline`,
        { notificationDays: Number(days) },
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'claim-notification-timeline', productId] });
      toast({ title: 'Timeline saved' });
    },
    onError: () => toast({ title: 'Could not save timeline', variant: 'destructive' }),
  });

  const daysNum = Number(days);
  const invalid = !productId || !days || Number.isNaN(daysNum) || daysNum < 1;

  return (
    <PageSection title="Claim Notification SLA" description="Days a claim must be notified within after the loss, per product.">
      <div className="max-w-md space-y-4">
        <div className="space-y-2">
          <Label>Product</Label>
          <Select value={productId} onValueChange={(v) => { setProductId(v); setDays(''); }}>
            <SelectTrigger><SelectValue placeholder="Select a product…" /></SelectTrigger>
            <SelectContent>{products.map((p) => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</SelectContent>
          </Select>
        </div>
        {!productId
          ? <EmptyState title="Select a product" description="Pick a product to configure its notification SLA." />
          : timelineQuery.isLoading
          ? <Skeleton className="h-10 w-full" />
          : (
            <div className="space-y-2">
              <Label>Notification window (days)</Label>
              <Input type="number" min={1} value={days} onChange={(e) => setDays(e.target.value)} placeholder="e.g. 14" />
              <div className="flex justify-end pt-2">
                <Button size="sm" disabled={invalid || save.isPending} onClick={() => save.mutate()}>{save.isPending ? 'Saving…' : 'Save'}</Button>
              </div>
            </div>
          )}
      </div>
    </PageSection>
  );
}

// ── Required Documents (shape C — per-product list, per-row CRUD) ─────────────

function DocumentsTab() {
  const [productId, setProductId] = useState<string>('');
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<ClaimDocumentRequirementDto | null>(null);

  const productsQuery = useQuery<ProductDto[]>({
    queryKey: ['setup', 'products'],
    queryFn: async () => (await apiClient.get<{ data: ProductDto[] }>('/api/v1/setup/products')).data.data,
  });
  const products = productsQuery.data ?? [];

  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<ClaimDocumentRequirementDto>({
    endpoint: (id) => `/api/v1/setup/products/${productId}/claim-document-requirements/${id}`,
    invalidateKey: ['setup', 'claim-document-requirements', productId],
    entityLabel: 'Required Document',
    entityName: (d) => d.documentName,
  });

  const docsQuery = useQuery<ClaimDocumentRequirementDto[]>({
    queryKey: ['setup', 'claim-document-requirements', productId],
    enabled: !!productId,
    queryFn: async () => (await apiClient.get<{ data: ClaimDocumentRequirementDto[] }>(`/api/v1/setup/products/${productId}/claim-document-requirements`)).data.data,
  });
  const rows = docsQuery.data ?? [];

  const columns: ColumnDef<ClaimDocumentRequirementDto>[] = [
    { accessorKey: 'documentName', header: ({ column }) => <DataTableColumnHeader column={column} title="Document" />, cell: ({ row }) => <span className="font-medium">{row.original.documentName}</span> },
    { accessorKey: 'documentType', header: ({ column }) => <DataTableColumnHeader column={column} title="Type" />, cell: ({ row }) => <span>{(row.original.documentType || '—').replace(/_/g, ' ')}</span> },
    { accessorKey: 'mandatory', header: ({ column }) => <DataTableColumnHeader column={column} title="Mandatory" />, cell: ({ row }) => <Badge variant={row.original.mandatory ? 'default' : 'outline'}>{row.original.mandatory ? 'Yes' : 'No'}</Badge> },
    { id: 'actions', cell: ({ row }) => <DataTableRowActions row={row} actions={[
      { label: 'Edit', onClick: (r) => { setEditing(r.original); setSheetOpen(true); } },
      { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
    ]} /> },
  ];

  return (
    <>
      <div className="flex items-center justify-between mb-3 gap-3">
        <Select value={productId} onValueChange={(v) => setProductId(v)}>
          <SelectTrigger className="w-64"><SelectValue placeholder="Select a product…" /></SelectTrigger>
          <SelectContent>{products.map((p) => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</SelectContent>
        </Select>
        <Button size="sm" disabled={!productId} onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Document</Button>
      </div>
      {!productId
        ? <EmptyState title="Select a product" description="Pick a product to manage its required claim documents." />
        : docsQuery.isLoading
        ? <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
        : rows.length === 0
        ? <EmptyState title="No required documents yet" description="Add the documents claims on this product must supply." />
        : <DataTable columns={columns} data={rows} toolbar={{ searchColumn: 'documentName', searchPlaceholder: 'Search documents…' }} />}
      {productId && <ClaimDocumentRequirementSheet open={sheetOpen} onOpenChange={setSheetOpen} productId={productId} requirement={editing} onSuccess={() => setSheetOpen(false)} />}
      {deleteDialog}
    </>
  );
}

// ── Page ─────────────────────────────────────────────────────────────────────

export default function ClaimsConfigPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader title="Claims Configuration" description="Set up reserve categories, notification timelines, document requirements and loss types." />
      <Tabs defaultValue="reserves">
        <TabsList>
          <TabsTrigger value="reserves">Reserve Categories</TabsTrigger>
          <TabsTrigger value="timelines">Notification Timelines</TabsTrigger>
          <TabsTrigger value="documents">Required Documents</TabsTrigger>
          <TabsTrigger value="loss">Nature / Cause of Loss</TabsTrigger>
        </TabsList>
        <TabsContent value="reserves"  className="mt-4"><ReservesTab /></TabsContent>
        <TabsContent value="timelines" className="mt-4"><TimelinesTab /></TabsContent>
        <TabsContent value="documents" className="mt-4"><DocumentsTab /></TabsContent>
        <TabsContent value="loss"      className="mt-4"><LossTab /></TabsContent>
      </Tabs>
    </div>
  );
}
