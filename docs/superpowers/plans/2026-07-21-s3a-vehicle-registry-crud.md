# S3a — Vehicle Registry CRUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the `VehicleRegistryPage` (currently 3 `PlaceholderTab`s with dead Add buttons) to full CRUD against the existing backend — Makes, Types, and Models.

**Architecture:** Pure frontend. Mirror the established Setup → Organisations tab pattern exactly: per-entity `useQuery` list → `DataTable` with Edit/Delete row actions → an `{Entity}Sheet` create/edit `Sheet` (RHF + Zod) → soft-delete via `useDeleteWithReason`. Makes & Types are simple `{id,name}` list-CRUD (shape A); Models is nested under a selected make (shape B).

**Tech Stack:** React + TanStack Query + TanStack Table + RHF + Zod + shadcn (`@cia/ui`) + Vitest.

## Global Constraints

- Backend already exists — **no backend changes**. Endpoints: `GET/POST /api/v1/setup/vehicle-makes`, `PUT/DELETE /api/v1/setup/vehicle-makes/{id}`; same for `/vehicle-types`; models nested: `GET/POST /api/v1/setup/vehicle-makes/{makeId}/models`, `PUT/DELETE /api/v1/setup/vehicle-makes/{makeId}/models/{id}`.
- Request bodies are `{ name: string }` (make/type/model). Responses: make/type `{id,name,createdAt,updatedAt}`; model `{id,name,makeId,makeName,createdAt,updatedAt}`.
- `check-api-wiring.sh`: no `console.log`, no top-level `mockX` in module files. `check-dto-drift.mjs`: every `*Dto` must match its backend `*Response`. Both + `pnpm --filter @cia/back-office build` must pass.
- Date cells null-tolerant (use `formatDate`/`formatTimestamp` from `@/lib/format` if rendering dates).
- Delete uses `useDeleteWithReason` (soft delete + `?reason=`).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

- **Modify** `cia-frontend/packages/api-client/src/modules/setup.ts` — add `VehicleMakeDto`, `VehicleTypeDto`, `VehicleModelDto` (interfaces + re-export via the barrel, matching `BranchDto`).
- **Create** `.../setup/pages/vehicle-registry/VehicleMakeSheet.tsx`, `VehicleTypeSheet.tsx`, `VehicleModelSheet.tsx` — create/edit sheets (mirror `BranchSheet.tsx`).
- **Rewrite** `.../setup/pages/vehicle-registry/VehicleRegistryPage.tsx` — 3 real CRUD tabs (mirror `OrganisationsPage.tsx` per-tab structure).
- **Create** `.../setup/pages/vehicle-registry/VehicleRegistryPage.test.tsx` — Vitest for the Makes list + create flow.

---

### Task 1: api-client DTOs

**Files:** Modify `cia-frontend/packages/api-client/src/modules/setup.ts` (near the other setup DTOs, e.g. after `BranchDto`).

**Produces:** `VehicleMakeDto`, `VehicleTypeDto`, `VehicleModelDto` (imported by all later tasks).

- [ ] **Step 1:** Add the three interfaces (fields exactly matching the backend `*Response` records so `check-dto-drift` maps `VehicleMakeDto`→`VehicleMakeResponse` cleanly):

```ts
export interface VehicleMakeDto {
  id:         string;
  name:       string;
  createdAt:  string;
  updatedAt?: string | null;
}

export interface VehicleTypeDto {
  id:         string;
  name:       string;
  createdAt:  string;
  updatedAt?: string | null;
}

export interface VehicleModelDto {
  id:         string;
  name:       string;
  makeId:     string;
  makeName:   string;
  createdAt:  string;
  updatedAt?: string | null;
}
```

- [ ] **Step 2:** Confirm the package barrel re-exports `./modules/setup` (it already does — `BranchDto` resolves from `@cia/api-client`). No barrel edit needed.
- [ ] **Step 3:** Run `node cia-frontend/scripts/check-dto-drift.mjs` — expect `✓ No DTO drift`. If the `updatedAt` nullability trips it, match the backend record exactly (the `*Response` `updatedAt` is a nullable `Instant` → `updatedAt?: string | null`).
- [ ] **Step 4:** Commit: `feat(api-client): VehicleMake/Type/Model DTOs`.

---

### Task 2: VehicleMakeSheet + Makes tab (shape A)

**Files:** Create `VehicleMakeSheet.tsx`; rewrite the Makes portion of `VehicleRegistryPage.tsx`.
**Interfaces:** Consumes `VehicleMakeDto` (Task 1). Produces `MakesTab` (used by Task 5's test + the page).

- [ ] **Step 1: `VehicleMakeSheet.tsx`** (mirror `BranchSheet.tsx`; `{name}`-only):

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type VehicleMakeDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({ name: z.string().min(2, 'Required').max(100) });
type FormValues = z.infer<typeof schema>;

interface Props { open: boolean; onOpenChange: (v: boolean) => void; make: VehicleMakeDto | null; onSuccess: () => void }

export default function VehicleMakeSheet({ open, onOpenChange, make, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '' } });

  useEffect(() => { form.reset(make ? { name: make.name } : { name: '' }); }, [make, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (make) {
        const res = await apiClient.put<{ data: VehicleMakeDto }>(`/api/v1/setup/vehicle-makes/${make.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: VehicleMakeDto }>('/api/v1/setup/vehicle-makes', values);
      return res.data.data;
    },
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['setup', 'vehicle-makes'] }); onSuccess(); },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: make ? 'Could not update make' : 'Could not add make' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{make ? 'Edit Make' : 'Add Make'}</SheetTitle>
          <SheetDescription>Vehicle makes power the motor-class risk pickers.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem><FormLabel>Make Name</FormLabel><FormControl><Input placeholder="e.g. Toyota" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : make ? 'Save Changes' : 'Add Make'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 2: `MakesTab`** (a component inside `VehicleRegistryPage.tsx`, mirroring the Organisations Branches tab):

```tsx
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
      <div className="flex justify-end mb-3"><Button size="sm" onClick={() => { setEditing(null); setSheetOpen(true); }}>Add Make</Button></div>
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
```

- [ ] **Step 3:** Wire `<MakesTab />` into the `makes` `TabsContent` of `VehicleRegistryPage` (replace `<PlaceholderTab label="vehicle make" />`). Add the imports the tab needs: `useState`, `useQuery`, `useDeleteWithReason`, `apiClient`, `VehicleMakeDto`, `Button/DataTable/DataTableColumnHeader/DataTableRowActions/EmptyState/Skeleton` from `@cia/ui`, `ColumnDef` from `@tanstack/react-table`, `VehicleMakeSheet`.
- [ ] **Step 4:** `pnpm --filter @cia/back-office build` → pass. `bash cia-frontend/scripts/check-api-wiring.sh` → pass.
- [ ] **Step 5:** Commit: `feat(setup): vehicle makes CRUD tab`.

---

### Task 3: VehicleTypeSheet + Types tab (shape A)

**Files:** Create `VehicleTypeSheet.tsx`; wire the Types tab in `VehicleRegistryPage.tsx`.
**Interfaces:** Consumes `VehicleTypeDto`.

- [ ] **Step 1: `VehicleTypeSheet.tsx`** — identical shape to `VehicleMakeSheet`, swapping `make`→`type`, `VehicleMakeDto`→`VehicleTypeDto`, endpoint `/api/v1/setup/vehicle-types`, `invalidateKey ['setup','vehicle-types']`, title "Make"→"Type", placeholder "e.g. Toyota"→"e.g. Saloon / SUV / Truck":

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type VehicleTypeDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({ name: z.string().min(2, 'Required').max(100) });
type FormValues = z.infer<typeof schema>;

interface Props { open: boolean; onOpenChange: (v: boolean) => void; type: VehicleTypeDto | null; onSuccess: () => void }

export default function VehicleTypeSheet({ open, onOpenChange, type, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '' } });
  useEffect(() => { form.reset(type ? { name: type.name } : { name: '' }); }, [type, form]);
  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (type) return (await apiClient.put<{ data: VehicleTypeDto }>(`/api/v1/setup/vehicle-types/${type.id}`, values)).data.data;
      return (await apiClient.post<{ data: VehicleTypeDto }>('/api/v1/setup/vehicle-types', values)).data.data;
    },
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['setup', 'vehicle-types'] }); onSuccess(); },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: type ? 'Could not update type' : 'Could not add type' }),
  });
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader><SheetTitle>{type ? 'Edit Type' : 'Add Type'}</SheetTitle><SheetDescription>Vehicle body types used in motor underwriting.</SheetDescription></SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem><FormLabel>Type Name</FormLabel><FormControl><Input placeholder="e.g. Saloon / SUV / Truck" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : type ? 'Save Changes' : 'Add Type'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 2: `TypesTab`** — identical to `MakesTab` with `VehicleTypeDto`, endpoint `/api/v1/setup/vehicle-types`, `invalidateKey ['setup','vehicle-types']`, `entityLabel 'Vehicle Type'`, "Add Type", `VehicleTypeSheet`. (Repeat the `MakesTab` body verbatim with those swaps.)
- [ ] **Step 3:** Wire `<TypesTab />` into the `types` `TabsContent`.
- [ ] **Step 4:** build + `check-api-wiring` → pass.
- [ ] **Step 5:** Commit: `feat(setup): vehicle types CRUD tab`.

---

### Task 4: VehicleModelSheet + Models tab (shape B — nested under a make)

**Files:** Create `VehicleModelSheet.tsx`; wire the Models tab.
**Interfaces:** Consumes `VehicleMakeDto` (for the make selector) + `VehicleModelDto`.

- [ ] **Step 1: `VehicleModelSheet.tsx`** — like `VehicleMakeSheet`, but the POST/PUT/DELETE endpoints are nested under a `makeId` prop (the tab passes the currently-selected make). Body is `{name}`; `makeId` comes from the path:

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type VehicleModelDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({ name: z.string().min(1, 'Required').max(100) });
type FormValues = z.infer<typeof schema>;

interface Props { open: boolean; onOpenChange: (v: boolean) => void; makeId: string; makeName: string; model: VehicleModelDto | null; onSuccess: () => void }

export default function VehicleModelSheet({ open, onOpenChange, makeId, makeName, model, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '' } });
  useEffect(() => { form.reset(model ? { name: model.name } : { name: '' }); }, [model, form]);
  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const base = `/api/v1/setup/vehicle-makes/${makeId}/models`;
      if (model) return (await apiClient.put<{ data: VehicleModelDto }>(`${base}/${model.id}`, values)).data.data;
      return (await apiClient.post<{ data: VehicleModelDto }>(base, values)).data.data;
    },
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['setup', 'vehicle-models', makeId] }); onSuccess(); },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: model ? 'Could not update model' : 'Could not add model' }),
  });
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader><SheetTitle>{model ? 'Edit Model' : 'Add Model'}</SheetTitle><SheetDescription>Model of <span className="font-medium">{makeName}</span>.</SheetDescription></SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem><FormLabel>Model Name</FormLabel><FormControl><Input placeholder="e.g. Camry" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : model ? 'Save Changes' : 'Add Model'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 2: `ModelsTab`** — a make `Select` drives which make's models load; the list + CRUD are scoped to the selected make. Delete uses the nested endpoint:

```tsx
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
```

- [ ] **Step 3:** Wire `<ModelsTab />` into the `models` `TabsContent`. Add `Select/SelectTrigger/SelectValue/SelectContent/SelectItem` to the `@cia/ui` imports.
- [ ] **Step 4:** build + `check-api-wiring` + `check-dto-drift` → pass.
- [ ] **Step 5:** Commit: `feat(setup): vehicle models CRUD tab (nested under make)`.

---

### Task 5: Vitest — Makes list + create

**Files:** Create `.../vehicle-registry/VehicleRegistryPage.test.tsx`. Mirror `setup/pages/notifications/NotificationTemplateEditorSheet.test.tsx` for the `vi.mock('@cia/api-client', …)` + `QueryClientProvider` wrapper idiom (read it first).

- [ ] **Step 1: Write the test** — render the page, mock `apiClient.get` to return 2 makes, assert both names render on the Makes tab; click "Add Make", type a name, submit, assert `apiClient.post` was called with `/api/v1/setup/vehicle-makes` + `{ name }`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import VehicleRegistryPage from './VehicleRegistryPage';

const get = vi.fn();
const post = vi.fn();
vi.mock('@cia/api-client', () => ({ apiClient: { get: (...a: unknown[]) => get(...a), post: (...a: unknown[]) => post(...a) } }));

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><VehicleRegistryPage /></QueryClientProvider>);
}

describe('VehicleRegistryPage — Makes tab', () => {
  beforeEach(() => { get.mockReset(); post.mockReset(); cleanup();
    get.mockResolvedValue({ data: { data: [
      { id: 'm1', name: 'Toyota', createdAt: '2026-01-01T00:00:00Z' },
      { id: 'm2', name: 'Honda',  createdAt: '2026-01-01T00:00:00Z' },
    ] } });
    post.mockResolvedValue({ data: { data: { id: 'm3', name: 'Ford', createdAt: '2026-01-01T00:00:00Z' } } });
  });

  it('lists makes from the live endpoint', async () => {
    renderPage();
    expect(await screen.findByText('Toyota')).toBeInTheDocument();
    expect(screen.getByText('Honda')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/setup/vehicle-makes');
  });

  it('creates a make via POST { name }', async () => {
    renderPage();
    await screen.findByText('Toyota');
    fireEvent.click(screen.getByRole('button', { name: /add make/i }));
    fireEvent.change(await screen.findByPlaceholderText(/toyota/i), { target: { value: 'Ford' } });
    fireEvent.click(screen.getByRole('button', { name: /^add make$/i }));
    await waitFor(() => expect(post).toHaveBeenCalledWith('/api/v1/setup/vehicle-makes', { name: 'Ford' }));
  });
});
```

- [ ] **Step 2:** Run `pnpm --filter @cia/back-office test -- VehicleRegistryPage` — both pass. (If `globals: false` in `vitest.config.ts`, the explicit `cleanup()` in `beforeEach` is required — verify against the config.)
- [ ] **Step 3:** Commit: `test(setup): vehicle registry makes list + create`.

---

## Self-Review notes

- **Spec coverage:** Makes (T2), Types (T3), Models nested (T4), DTOs (T1), Vitest (T5) — all 3 tabs + drift-safe DTOs + a test. ✓
- **Type consistency:** `VehicleMakeDto/VehicleTypeDto/VehicleModelDto` used identically across tasks; sheet prop names (`make`/`type`/`model`, `makeId`/`makeName`) consistent.
- **No placeholders:** every sheet + tab shown in full (Types repeats Makes verbatim with swaps, per the no-"similar-to" rule).
- **Backlog:** on completion, drain nothing yet — `setup-dead-shells` closes only after **S3b** (Claims Config) also lands; note "S3a done, S3b pending" in cia-log.
