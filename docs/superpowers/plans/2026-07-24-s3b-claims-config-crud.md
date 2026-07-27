# S3b — Claims Config CRUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire `ClaimsConfigPage` (currently 4 `PlaceholderTab`s with dead Add buttons) to full CRUD against the existing backend — Reserve Categories, Nature/Cause of Loss, Notification Timelines, Required Documents.

**Architecture:** Pure frontend. Reuses the S3a Vehicle Registry pattern (merged PR #50) as the template. Three UI shapes: **(A)** flat `{name,code}` list-CRUD (Reserve Categories, Nature of Loss); **(A′)** flat list-CRUD + a parent-FK `Select` in the sheet (Cause of Loss → `natureOfLossId`); **(B)** per-product **singleton** upsert form (Notification Timelines — GET + PUT, one `notificationDays` scalar); **(C)** per-product **list** with per-row CRUD (Required Documents).

**Tech Stack:** React + TanStack Query + TanStack Table + RHF + Zod + shadcn (`@cia/ui`) + Vitest.

## Global Constraints

- Backend already exists — **no backend changes**. Endpoints (all under `/api/v1/setup`, envelope `ApiResponse<T>` → unwrap `res.data.data`; list endpoints return a **flat array** in `data`, never a Page):
  - `claim-reserve-categories` — GET(list)/GET(id)/POST/PUT(id)/DELETE(id); body `{name,code}`.
  - `nature-of-loss` — same CRUD; body `{name,code}`.
  - `cause-of-loss` — same CRUD + `GET /by-nature/{natureOfLossId}`; body `{name,code,natureOfLossId}`.
  - `products/{productId}/claim-notification-timeline` — **GET + PUT only** (upsert, returns a default if unset); body `{notificationDays:number}`.
  - `products/{productId}/claim-document-requirements` — GET(list)/GET(id)/POST/PUT(id)/DELETE(id); body `{documentName,mandatory,documentType?}`.
  - Products for the per-product Selects: `GET /api/v1/setup/products` → `ProductDto[]` (`{id,name,...}`, already in `@cia/api-client`).
- `check-api-wiring.sh`: no `console.log`, no top-level `mockX`/`MOCK_X` in module files. `check-dto-drift.mjs`: every `*Dto` must match its backend `*Response` field set. Both + `pnpm --filter @cia/back-office build` must pass.
- Delete uses `useDeleteWithReason` — import path **`@/lib/use-delete-with-reason`** (kebab-case). Reference pattern: `setup/pages/vehicle-registry/VehicleRegistryPage.tsx` (S3a) + `setup/pages/organisations/SbuSheet.tsx` (a `{name,code}` sheet).
- Vitest mirrors `VehicleRegistryPage.test.tsx` (`importOriginal` `@cia/ui`, mock only `DataTable`/`DataTableRowActions`; scope a sheet submit `within(role="dialog")` when it shares a label with a trigger).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

- **Modify** `cia-frontend/packages/api-client/src/modules/setup.ts` — add `ClaimReserveCategoryDto`, `NatureOfLossDto`, `CauseOfLossDto`, `ClaimNotificationTimelineDto`, `ClaimDocumentRequirementDto`.
- **Create** under `.../setup/pages/claims-config/`: `ClaimReserveCategorySheet.tsx`, `NatureOfLossSheet.tsx`, `CauseOfLossSheet.tsx`, `ClaimDocumentRequirementSheet.tsx`.
- **Rewrite** `.../setup/pages/claims-config/ClaimsConfigPage.tsx` — 4 real CRUD tabs.
- **Create** `.../setup/pages/claims-config/ClaimsConfigPage.test.tsx` — Vitest for the Reserve Categories list + create flow.

Notification Timelines has **no sheet** — it is an inline product-scoped single-field form inside the tab.

---

### Task 1: api-client DTOs

**Files:** Modify `cia-frontend/packages/api-client/src/modules/setup.ts` (near the other setup DTOs).

**Produces:** the 5 DTOs consumed by all later tasks.

- [ ] **Step 1:** Add the five interfaces (fields exactly matching the backend `*Response`):

```ts
// Mirrors com.nubeero.cia.setup.loss.dto.ClaimReserveCategoryResponse.
export interface ClaimReserveCategoryDto {
  id:         string;
  name:       string;
  code:       string;
  createdAt:  string;
  updatedAt?: string | null;
}

// Mirrors com.nubeero.cia.setup.loss.dto.NatureOfLossResponse.
export interface NatureOfLossDto {
  id:         string;
  name:       string;
  code:       string;
  createdAt:  string;
  updatedAt?: string | null;
}

// Mirrors com.nubeero.cia.setup.loss.dto.CauseOfLossResponse. FK-linked to a nature.
export interface CauseOfLossDto {
  id:              string;
  name:            string;
  code:            string;
  natureOfLossId:  string;
  natureOfLossName: string;
  createdAt:       string;
  updatedAt?:      string | null;
}

// Mirrors com.nubeero.cia.setup.product.dto.ClaimNotificationTimelineResponse (per-product singleton).
export interface ClaimNotificationTimelineDto {
  id:               string;
  productId:        string;
  notificationDays: number;
  createdAt:        string;
  updatedAt?:       string | null;
}

// Mirrors com.nubeero.cia.setup.product.dto.ClaimDocumentRequirementResponse (per-product list row).
export interface ClaimDocumentRequirementDto {
  id:           string;
  productId:    string;
  documentName: string;
  mandatory:    boolean;
  documentType: string;
  createdAt:    string;
  updatedAt?:   string | null;
}
```

- [ ] **Step 2:** Run `node cia-frontend/scripts/check-dto-drift.mjs` — expect `✓ No DTO drift`. (If a backend `*Response` uses a non-default class name, add a `manualMap` entry in `dto-drift.config.json`; the default `Dto→Response` swap should map all five cleanly.)
- [ ] **Step 3:** Commit: `feat(api-client): claims-config DTOs (reserve/loss/timeline/document)`.

---

### Task 2: Reserve Categories tab (shape A)

**Files:** Create `ClaimReserveCategorySheet.tsx`; add `ReservesTab` to `ClaimsConfigPage.tsx`.
**Interfaces:** Consumes `ClaimReserveCategoryDto`.

- [ ] **Step 1: `ClaimReserveCategorySheet.tsx`** (`{name,code}` — mirror `organisations/SbuSheet.tsx`):

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type ClaimReserveCategoryDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name: z.string().min(2, 'Required').max(100),
  code: z.string().min(2, 'Required').max(20),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  category: ClaimReserveCategoryDto | null;
  onSuccess: () => void;
}

export default function ClaimReserveCategorySheet({ open, onOpenChange, category, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '', code: '' } });

  useEffect(() => { form.reset(category ? { name: category.name, code: category.code } : { name: '', code: '' }); }, [category, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (category) {
        const res = await apiClient.put<{ data: ClaimReserveCategoryDto }>(`/api/v1/setup/claim-reserve-categories/${category.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: ClaimReserveCategoryDto }>('/api/v1/setup/claim-reserve-categories', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'claim-reserve-categories'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: category ? 'Could not update category' : 'Could not add category' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{category ? 'Edit Reserve Category' : 'Add Reserve Category'}</SheetTitle>
          <SheetDescription>Reserve categories bucket claim reserves for reporting.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Name</FormLabel><FormControl><Input placeholder="e.g. Bodily Injury" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="BI" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : category ? 'Save Changes' : 'Add Category'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 2: `ReservesTab`** (component in `ClaimsConfigPage.tsx`, mirror S3a `MakesTab`):

```tsx
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
```

- [ ] **Step 3:** Wire `<ReservesTab />` into the `reserves` `TabsContent` (replace `<PlaceholderTab label="reserve category" />`). Add the imports (mirror S3a's import block: `useState`, `useQuery`, `useDeleteWithReason` from `@/lib/use-delete-with-reason`, `apiClient` + the DTOs, `Button/DataTable/DataTableColumnHeader/DataTableRowActions/EmptyState/Skeleton` from `@cia/ui`, `ColumnDef`, the sheet).
- [ ] **Step 4:** `pnpm --filter @cia/back-office build` + `bash cia-frontend/scripts/check-api-wiring.sh` → pass.
- [ ] **Step 5:** Commit: `feat(setup): claim reserve categories CRUD tab`.

---

### Task 3: Nature / Cause of Loss tab (shapes A + A′)

**Files:** Create `NatureOfLossSheet.tsx` + `CauseOfLossSheet.tsx`; add `LossTab` to `ClaimsConfigPage.tsx`.
**Interfaces:** Consumes `NatureOfLossDto`, `CauseOfLossDto`. The tab stacks two sections — Nature of Loss (flat `{name,code}`) then Cause of Loss (flat `{name,code}` + a required Nature `Select`, with a `natureOfLossName` column).

- [ ] **Step 1: `NatureOfLossSheet.tsx`** — identical to `ClaimReserveCategorySheet` with `NatureOfLossDto`, endpoint `/api/v1/setup/nature-of-loss`, `invalidateKey ['setup','nature-of-loss']`, titles "Nature of Loss", placeholders "e.g. Fire" / "FIRE". (Repeat the ClaimReserveCategorySheet body verbatim with those swaps; `category`→`nature` prop.)

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type NatureOfLossDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({ name: z.string().min(2, 'Required').max(100), code: z.string().min(2, 'Required').max(20) });
type FormValues = z.infer<typeof schema>;

interface Props { open: boolean; onOpenChange: (v: boolean) => void; nature: NatureOfLossDto | null; onSuccess: () => void }

export default function NatureOfLossSheet({ open, onOpenChange, nature, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '', code: '' } });
  useEffect(() => { form.reset(nature ? { name: nature.name, code: nature.code } : { name: '', code: '' }); }, [nature, form]);
  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (nature) return (await apiClient.put<{ data: NatureOfLossDto }>(`/api/v1/setup/nature-of-loss/${nature.id}`, values)).data.data;
      return (await apiClient.post<{ data: NatureOfLossDto }>('/api/v1/setup/nature-of-loss', values)).data.data;
    },
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['setup', 'nature-of-loss'] }); onSuccess(); },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: nature ? 'Could not update nature' : 'Could not add nature' }),
  });
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader><SheetTitle>{nature ? 'Edit Nature of Loss' : 'Add Nature of Loss'}</SheetTitle><SheetDescription>High-level loss categories (Fire, Motor Accident, Theft…).</SheetDescription></SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Name</FormLabel><FormControl><Input placeholder="e.g. Fire" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="FIRE" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : nature ? 'Save Changes' : 'Add Nature'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 2: `CauseOfLossSheet.tsx`** — `{name,code}` + a required Nature `Select` (fetches natures; `natureOfLossId`):

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type CauseOfLossDto, type NatureOfLossDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name: z.string().min(2, 'Required').max(100),
  code: z.string().min(2, 'Required').max(20),
  natureOfLossId: z.string().min(1, 'Select a nature of loss'),
});
type FormValues = z.infer<typeof schema>;

interface Props { open: boolean; onOpenChange: (v: boolean) => void; cause: CauseOfLossDto | null; onSuccess: () => void }

export default function CauseOfLossSheet({ open, onOpenChange, cause, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '', code: '', natureOfLossId: '' } });

  const naturesQuery = useQuery<NatureOfLossDto[]>({
    queryKey: ['setup', 'nature-of-loss'],
    queryFn: async () => (await apiClient.get<{ data: NatureOfLossDto[] }>('/api/v1/setup/nature-of-loss')).data.data,
  });
  const natures = naturesQuery.data ?? [];

  useEffect(() => {
    form.reset(cause ? { name: cause.name, code: cause.code, natureOfLossId: cause.natureOfLossId } : { name: '', code: '', natureOfLossId: '' });
  }, [cause, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (cause) return (await apiClient.put<{ data: CauseOfLossDto }>(`/api/v1/setup/cause-of-loss/${cause.id}`, values)).data.data;
      return (await apiClient.post<{ data: CauseOfLossDto }>('/api/v1/setup/cause-of-loss', values)).data.data;
    },
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['setup', 'cause-of-loss'] }); onSuccess(); },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: cause ? 'Could not update cause' : 'Could not add cause' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader><SheetTitle>{cause ? 'Edit Cause of Loss' : 'Add Cause of Loss'}</SheetTitle><SheetDescription>Specific causes under a nature of loss (drives the claim cascading dropdown).</SheetDescription></SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Name</FormLabel><FormControl><Input placeholder="e.g. Electrical Fault" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="ELEC" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <FormField control={form.control} name="natureOfLossId" render={({ field }) => (
              <FormItem>
                <FormLabel>Nature of Loss</FormLabel>
                <Select onValueChange={field.onChange} value={field.value || ''}>
                  <FormControl><SelectTrigger><SelectValue placeholder="Select a nature…" /></SelectTrigger></FormControl>
                  <SelectContent>{natures.map((n) => (<SelectItem key={n.id} value={n.id}>{n.name} ({n.code})</SelectItem>))}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : cause ? 'Save Changes' : 'Add Cause'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 3: `LossTab`** — two stacked `PageSection`s (Nature list on top, Cause list below with a `natureOfLossName` column). Uses `PageSection` from `@cia/ui`:

```tsx
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
```

- [ ] **Step 4:** Wire `<LossTab />` into the `loss` `TabsContent`. Add `PageSection`, `Select*` (already needed by the sheet import, but LossTab itself needs `PageSection`) to the `@cia/ui` imports and the two sheets to the file imports.
- [ ] **Step 5:** build + `check-api-wiring` → pass.
- [ ] **Step 6:** Commit: `feat(setup): nature + cause of loss CRUD tab`.

---

### Task 4: Notification Timelines tab (shape B — per-product singleton)

**Files:** Add `TimelinesTab` to `ClaimsConfigPage.tsx` (no sheet — inline product-scoped form).
**Interfaces:** Consumes `ClaimNotificationTimelineDto` + `ProductDto`.

- [ ] **Step 1: `TimelinesTab`** — a product `Select` drives GET `/products/{id}/claim-notification-timeline`; a single `notificationDays` number field saves via PUT (upsert). React Query `enabled: !!productId`; the form syncs from the fetched value via `useEffect`:

```tsx
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
        {productId && (
          timelineQuery.isLoading
            ? <Skeleton className="h-10 w-full" />
            : (
              <div className="space-y-2">
                <Label>Notification window (days)</Label>
                <Input type="number" min={1} value={days} onChange={(e) => setDays(e.target.value)} placeholder="e.g. 14" />
                <div className="flex justify-end pt-2">
                  <Button size="sm" disabled={invalid || save.isPending} onClick={() => save.mutate()}>{save.isPending ? 'Saving…' : 'Save'}</Button>
                </div>
              </div>
            )
        )}
        {!productId && <EmptyState title="Select a product" description="Pick a product to configure its notification SLA." />}
      </div>
    </PageSection>
  );
}
```

- [ ] **Step 2:** Wire `<TimelinesTab />` into the `timelines` `TabsContent`. Add `Label`, `toast`, `PageSection` (from `@cia/ui`) and `ProductDto` to imports. (Verified: `toast` is a named `@cia/ui` export called as `toast({ title, variant, description })` — same idiom as `finance/pages/ReverseTransactionDialog.tsx`.)
- [ ] **Step 3:** build + `check-api-wiring` → pass.
- [ ] **Step 4:** Commit: `feat(setup): claim notification timeline per-product upsert tab`.

---

### Task 5: Required Documents tab (shape C — per-product list, per-row CRUD)

**Files:** Create `ClaimDocumentRequirementSheet.tsx`; add `DocumentsTab` to `ClaimsConfigPage.tsx`.
**Interfaces:** Consumes `ClaimDocumentRequirementDto` + `ProductDto`.

- [ ] **Step 1: `ClaimDocumentRequirementSheet.tsx`** — `documentName` + `mandatory` (`Switch`) + `documentType` (`Select` over the 8 `ClaimDocumentType` enum values). Endpoints nested under `productId`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Switch,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type ClaimDocumentRequirementDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

// ClaimDocumentType enum (cia-claims/ClaimDocumentType.java) — UI option list, not API data.
const DOCUMENT_TYPES = [
  'CLAIM_FORM', 'POLICE_REPORT', 'SURVEY_REPORT', 'MEDICAL_REPORT',
  'PHOTOS', 'REPAIR_ESTIMATE', 'DISCHARGE_VOUCHER', 'OTHER',
] as const;

const schema = z.object({
  documentName: z.string().min(2, 'Required').max(150),
  mandatory:    z.boolean(),
  documentType: z.string().min(1, 'Select a type'),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  productId: string;
  requirement: ClaimDocumentRequirementDto | null;
  onSuccess: () => void;
}

export default function ClaimDocumentRequirementSheet({ open, onOpenChange, productId, requirement, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { documentName: '', mandatory: true, documentType: 'CLAIM_FORM' } });

  useEffect(() => {
    form.reset(requirement
      ? { documentName: requirement.documentName, mandatory: requirement.mandatory, documentType: requirement.documentType || 'OTHER' }
      : { documentName: '', mandatory: true, documentType: 'CLAIM_FORM' });
  }, [requirement, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const base = `/api/v1/setup/products/${productId}/claim-document-requirements`;
      if (requirement) return (await apiClient.put<{ data: ClaimDocumentRequirementDto }>(`${base}/${requirement.id}`, values)).data.data;
      return (await apiClient.post<{ data: ClaimDocumentRequirementDto }>(base, values)).data.data;
    },
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['setup', 'claim-document-requirements', productId] }); onSuccess(); },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: requirement ? 'Could not update document' : 'Could not add document' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader><SheetTitle>{requirement ? 'Edit Required Document' : 'Add Required Document'}</SheetTitle><SheetDescription>Documents a claim on this product must supply.</SheetDescription></SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="documentName" render={({ field }) => (
              <FormItem><FormLabel>Document Name</FormLabel><FormControl><Input placeholder="e.g. Police Report" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <FormField control={form.control} name="documentType" render={({ field }) => (
              <FormItem>
                <FormLabel>Type</FormLabel>
                <Select onValueChange={field.onChange} value={field.value || ''}>
                  <FormControl><SelectTrigger><SelectValue placeholder="Select a type…" /></SelectTrigger></FormControl>
                  <SelectContent>{DOCUMENT_TYPES.map((t) => (<SelectItem key={t} value={t}>{t.replace(/_/g, ' ')}</SelectItem>))}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />
            <FormField control={form.control} name="mandatory" render={({ field }) => (
              <FormItem className="flex items-center justify-between rounded-md border p-3">
                <div><FormLabel>Mandatory</FormLabel><FormDescription>Block claim approval until supplied.</FormDescription></div>
                <FormControl><Switch checked={field.value} onCheckedChange={field.onChange} /></FormControl>
              </FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : requirement ? 'Save Changes' : 'Add Document'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 2: `DocumentsTab`** — a product `Select` drives the per-product list + CRUD (mirror S3a `ModelsTab`, with a `mandatory` Yes/No column):

```tsx
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
```

- [ ] **Step 3:** Wire `<DocumentsTab />` into the `documents` `TabsContent`. Add `Badge`, `Switch` (sheet), `FormDescription` (sheet) to imports.
- [ ] **Step 4:** build + `check-api-wiring` + `check-dto-drift` → pass.
- [ ] **Step 5:** Commit: `feat(setup): claim required-documents per-product CRUD tab`.

---

### Task 6: Vitest — Reserve Categories list + create

**Files:** Create `.../claims-config/ClaimsConfigPage.test.tsx`. Mirror `vehicle-registry/VehicleRegistryPage.test.tsx` (S3a) exactly — `importOriginal` `@cia/ui`, mock only `DataTable`/`DataTableRowActions`; mock `@cia/api-client`.

- [ ] **Step 1: Write the test** — default tab is `reserves`, so `ReservesTab` mounts and fetches `claim-reserve-categories`. Assert both categories render; open "Add Category", fill name+code, submit (scoped `within(dialog)`), assert `POST /api/v1/setup/claim-reserve-categories {name,code}`:

```tsx
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import ClaimsConfigPage from './ClaimsConfigPage';
import type { ClaimReserveCategoryDto } from '@cia/api-client';

vi.mock('@cia/ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@cia/ui')>();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function DataTable({ columns, data }: { columns: any[]; data: any[] }) {
    return React.createElement('table', null, React.createElement('tbody', null,
      data.map((rowData, i) => {
        const row = { original: rowData };
        return React.createElement('tr', { key: rowData.id ?? i },
          columns.map((col, j) => {
            const ctx = { getValue: () => (col.accessorKey ? rowData[col.accessorKey] : undefined), row };
            const content = col.cell ? col.cell(ctx) : ctx.getValue();
            return React.createElement('td', { key: col.id ?? col.accessorKey ?? j }, content);
          }));
      })));
  }
  function DataTableRowActions({
    row, actions,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }: { row: any; actions: { label: string; onClick: (row: any) => void }[] }) {
    return React.createElement('div', null,
      actions.map((a, i) => React.createElement('button', { key: i, onClick: () => a.onClick(row) }, a.label)));
  }
  return { ...actual, DataTable, DataTableRowActions };
});

const get = vi.fn();
const post = vi.fn();
vi.mock('@cia/api-client', () => ({
  apiClient: { get: (...a: unknown[]) => get(...a), post: (...a: unknown[]) => post(...a), put: vi.fn(), delete: vi.fn() },
}));

const categories: ClaimReserveCategoryDto[] = [
  { id: 'c1', name: 'Bodily Injury', code: 'BI', createdAt: '2026-01-01T00:00:00Z' },
  { id: 'c2', name: 'Property Damage', code: 'PD', createdAt: '2026-01-01T00:00:00Z' },
];

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

describe('ClaimsConfigPage — Reserve Categories tab', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
    get.mockResolvedValue({ data: { data: categories } });
    post.mockResolvedValue({ data: { data: { id: 'c3', name: 'Legal', code: 'LG', createdAt: '2026-01-01T00:00:00Z' } } });
  });

  it('lists reserve categories from the live endpoint', async () => {
    render(React.createElement(ClaimsConfigPage), { wrapper });
    expect(await screen.findByText('Bodily Injury')).toBeInTheDocument();
    expect(screen.getByText('Property Damage')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/setup/claim-reserve-categories');
  });

  it('creates a category via POST { name, code }', async () => {
    const user = userEvent.setup();
    render(React.createElement(ClaimsConfigPage), { wrapper });
    await screen.findByText('Bodily Injury');
    await user.click(screen.getByRole('button', { name: /add category/i }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByPlaceholderText(/bodily injury/i), 'Legal');
    await user.type(within(dialog).getByPlaceholderText(/^bi$/i), 'LG');
    await user.click(within(dialog).getByRole('button', { name: /^add category$/i }));
    await waitFor(() => expect(post).toHaveBeenCalledWith('/api/v1/setup/claim-reserve-categories', { name: 'Legal', code: 'LG' }));
  });
});
```

- [ ] **Step 2:** Run the full suite `pnpm --filter @cia/back-office test` — all pass, coverage above floors (running a single file with `--coverage` trips the floor; the full suite is the gate, as in S3a).
- [ ] **Step 3:** Commit: `test(setup): claims-config reserve categories list + create`.

---

## Self-Review notes

- **Spec coverage:** all 4 tabs — Reserves (T2), Nature/Cause (T3), Timelines (T4), Documents (T5) — + DTOs (T1) + Vitest (T6). ✓
- **Type consistency:** DTO field names match the subagent-verified backend `*Response` sets (esp. `CauseOfLossDto.natureOfLossName`, both `productId` fields, `notificationDays:number`, `mandatory:boolean`, `documentType:string`); sheet prop names consistent (`category`/`nature`/`cause`/`requirement`, `productId`).
- **Shapes covered:** A (Reserves, Nature), A′ (Cause + nature Select), B (Timelines per-product singleton PUT), C (Documents per-product list). The two per-product tabs (`Timelines`, `Documents`) both fetch `/api/v1/setup/products` — same `['setup','products']` queryKey so React Query dedupes.
- **check-api-wiring:** `DOCUMENT_TYPES` is a UI enum-option constant (not `mockX`/`MOCK_X`) — won't trip the guard; no `console.log`.
- **Open verification during build (not blockers):** (1) confirm `@cia/ui` exports `PageSection`, `Switch`, `FormDescription`, `Label`, and the `toast` import shape (T4) against an existing caller before relying on them — swap to `useToast()` if that's the app's idiom; (2) `documentType` is always present (Java primitive-backed enum name) so no null-tolerance needed, but the column still guards with `|| '—'`.
- **Backlog:** on completion, **close `setup-dead-shells`** (S3a did the Vehicle Registry half; S3b does Claims Config — the row's other half). No new rows expected; log any side-discovery (e.g. the per-product `products` list is `@PageableDefault(size=20)` — subsumed by `list-endpoints-true-pagination`).
