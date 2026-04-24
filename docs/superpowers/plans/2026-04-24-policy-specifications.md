# Policy Specifications — Setup Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Policy Specifications sub-page in the Setup module — a searchable global Clause Bank (CRUD via ClauseSheet) and a per-product Templates manager (upload/download/archive via TemplateUploadSheet).

**Architecture:** Single page (`PolicySpecificationsPage`) with two tabs. Clause Bank owns its domain types and uses DataTable with a hand-rolled toolbar for client-side search + product/type filtering. Templates tab owns its types and renders a custom card list (not DataTable) with a product selector. Both sheets use react-hook-form + Zod. All state is local (mock data, no API calls).

**Tech Stack:** React 18, TypeScript, react-hook-form v7, Zod, @cia/ui (DataTable, DataTableColumnHeader, DataTableRowActions, Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, Tabs, TabsList, TabsTrigger, TabsContent, Card, CardContent, CardHeader, CardTitle, Button, Input, Textarea, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Switch, Checkbox, Badge, Separator, EmptyState, PageHeader, Form, FormField, FormItem, FormLabel, FormControl, FormMessage), TanStack Table (via DataTable)

---

## File Map

| Action | Path | Responsibility |
|---|---|---|
| Create | `setup/pages/policy-specs/PolicySpecificationsPage.tsx` | PageHeader + Tabs shell; mounts ClauseBankTab and TemplatesTab |
| Create | `setup/pages/policy-specs/clause-types.ts` | Shared types: ClauseRow, ClauseType, ClauseApplicability, ClauseSavePayload, PRODUCTS, CLAUSE_TYPES |
| Create | `setup/pages/policy-specs/ClauseBankTab.tsx` | Mock data; DataTable + hand-rolled toolbar + state; mounts ClauseSheet |
| Create | `setup/pages/policy-specs/ClauseSheet.tsx` | Create/edit clause drawer; react-hook-form + Zod |
| Create | `setup/pages/policy-specs/template-types.ts` | Shared types: TemplateRow, TemplateType, TEMPLATE_TYPES |
| Create | `setup/pages/policy-specs/TemplatesTab.tsx` | Mock data; product selector + card list; mounts TemplateUploadSheet |
| Create | `setup/pages/policy-specs/TemplateUploadSheet.tsx` | Upload template drawer; drag-and-drop file zone |
| Modify | `setup/layout/SetupLayout.tsx` | Add "Policy Specifications" nav item under Products group |
| Modify | `setup/index.tsx` | Add lazy import + Route for policy-specifications |

All paths are relative to `cia-frontend/apps/back-office/src/modules/`.

---

## Task 1: Wire nav item and route

**Files:**
- Modify: `setup/layout/SetupLayout.tsx`
- Modify: `setup/index.tsx`
- Create: `setup/pages/policy-specs/PolicySpecificationsPage.tsx` (shell only)

- [ ] **Step 1.1: Add nav item to SetupLayout**

In `setup/layout/SetupLayout.tsx`, find the `Products` group and add the new item:

```tsx
{
  label: 'Products',
  items: [
    { label: 'Classes of Business', path: '/setup/classes' },
    { label: 'Products',            path: '/setup/products' },
    { label: 'Policy Specifications', path: '/setup/policy-specifications' },
  ],
},
```

- [ ] **Step 1.2: Create the page shell**

Create `setup/pages/policy-specs/PolicySpecificationsPage.tsx`:

```tsx
export default function PolicySpecificationsPage() {
  return (
    <div className="p-6 space-y-5">
      <p className="text-sm text-muted-foreground">Loading…</p>
    </div>
  );
}
```

- [ ] **Step 1.3: Wire the route in setup/index.tsx**

```tsx
const PolicySpecificationsPage = lazy(() => import('./pages/policy-specs/PolicySpecificationsPage'));
```

Add to the `<Routes>` block (after the `products` route):

```tsx
<Route path="policy-specifications" element={<PolicySpecificationsPage />} />
```

- [ ] **Step 1.4: Typecheck**

```bash
cd cia-frontend && pnpm --filter @cia/back-office typecheck
```

Expected: exit 0. Fix any TypeScript errors before continuing.

- [ ] **Step 1.5: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/setup/
git commit -m "feat(setup): wire Policy Specifications route and nav item"
```

---

## Task 2: Page shell with tabs

**Files:**
- Modify: `setup/pages/policy-specs/PolicySpecificationsPage.tsx` (full implementation)
- Create: `setup/pages/policy-specs/ClauseBankTab.tsx` (placeholder)
- Create: `setup/pages/policy-specs/TemplatesTab.tsx` (placeholder)

- [ ] **Step 2.1: Create ClauseBankTab placeholder**

Create `setup/pages/policy-specs/ClauseBankTab.tsx`:

```tsx
export default function ClauseBankTab() {
  return <p className="text-sm text-muted-foreground py-4">Clause bank coming soon.</p>;
}
```

- [ ] **Step 2.2: Create TemplatesTab placeholder**

Create `setup/pages/policy-specs/TemplatesTab.tsx`:

```tsx
export default function TemplatesTab() {
  return <p className="text-sm text-muted-foreground py-4">Templates coming soon.</p>;
}
```

- [ ] **Step 2.3: Implement PolicySpecificationsPage**

Replace `setup/pages/policy-specs/PolicySpecificationsPage.tsx`:

```tsx
import { PageHeader, Tabs, TabsContent, TabsList, TabsTrigger } from '@cia/ui';
import ClauseBankTab from './ClauseBankTab';
import TemplatesTab from './TemplatesTab';

export default function PolicySpecificationsPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Policy Specifications"
        description="Manage the clause library and document templates used across all products."
      />
      <Tabs defaultValue="clauses">
        <TabsList>
          <TabsTrigger value="clauses">Clause Bank</TabsTrigger>
          <TabsTrigger value="templates">Templates</TabsTrigger>
        </TabsList>
        <TabsContent value="clauses" className="mt-4">
          <ClauseBankTab />
        </TabsContent>
        <TabsContent value="templates" className="mt-4">
          <TemplatesTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
```

- [ ] **Step 2.4: Typecheck**

```bash
cd cia-frontend && pnpm --filter @cia/back-office typecheck
```

Expected: exit 0.

- [ ] **Step 2.5: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/
git commit -m "feat(setup): Policy Specifications page shell with tabs"
```

---

## Task 3: ClauseSheet

**Files:**
- Create: `setup/pages/policy-specs/ClauseSheet.tsx`

- [ ] **Step 3.1: Create ClauseSheet**

Create `setup/pages/policy-specs/ClauseSheet.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm, useController } from 'react-hook-form';
import { z } from 'zod';
import {
  Button,
  Checkbox,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Switch,
  Textarea,
  Input,
} from '@cia/ui';
import type { ClauseRow } from './ClauseBankTab';

// ── Constants ────────────────────────────────────────────────────────────────
export const CLAUSE_TYPES = [
  { value: 'STANDARD',          label: 'Standard' },
  { value: 'EXCLUSION',         label: 'Exclusion' },
  { value: 'SPECIAL_CONDITION', label: 'Special Condition' },
  { value: 'WARRANTY',          label: 'Warranty' },
] as const;

export const PRODUCTS = [
  { id: '1', name: 'Private Motor Comprehensive' },
  { id: '2', name: 'Commercial Vehicle' },
  { id: '3', name: 'Fire & Burglary Standard' },
  { id: '4', name: 'Marine Cargo Open Cover' },
];

// ── Schema ───────────────────────────────────────────────────────────────────
const clauseSchema = z.object({
  title:         z.string().min(2, 'Required'),
  text:          z.string().min(10, 'Required'),
  type:          z.enum(['STANDARD', 'EXCLUSION', 'SPECIAL_CONDITION', 'WARRANTY']),
  applicability: z.enum(['MANDATORY', 'OPTIONAL']),
  productIds:    z.array(z.string()).min(1, 'Select at least one product'),
});
type ClauseFormValues = z.infer<typeof clauseSchema>;

// ── Props ────────────────────────────────────────────────────────────────────
interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  clause:       ClauseRow | null;
  onSave:       (values: ClauseSavePayload) => void;
}

export default function ClauseSheet({ open, onOpenChange, clause, onSave }: Props) {
  const form = useForm<ClauseFormValues>({
    resolver:      zodResolver(clauseSchema) as any,
    defaultValues: { title: '', text: '', type: 'STANDARD', applicability: 'OPTIONAL', productIds: [] },
  });

  useEffect(() => {
    form.reset(
      clause
        ? { title: clause.title, text: clause.text, type: clause.type, applicability: clause.applicability, productIds: clause.productIds }
        : { title: '', text: '', type: 'STANDARD', applicability: 'OPTIONAL', productIds: [] },
    );
  }, [clause, open]);

  const { field: productIdsField } = useController({ name: 'productIds', control: form.control });

  function toggleProduct(id: string) {
    const current: string[] = productIdsField.value ?? [];
    productIdsField.onChange(
      current.includes(id) ? current.filter(p => p !== id) : [...current, id],
    );
  }

  function onSubmit(values: ClauseFormValues) {
    onSave({ title: values.title, text: values.text, type: values.type, applicability: values.applicability, productIds: values.productIds, id: clause?.id ?? undefined });
    onOpenChange(false);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-[440px] sm:max-w-[440px] overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{clause ? 'Edit Clause' : 'Add Clause'}</SheetTitle>
          <SheetDescription>
            {clause ? 'Update the clause details below.' : 'Define a new clause for the policy document library.'}
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">

            {/* Title */}
            <FormField control={form.control} name="title" render={({ field }) => (
              <FormItem>
                <FormLabel>Clause Title</FormLabel>
                <FormControl><Input placeholder="e.g. Third Party Liability" {...field} /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            {/* Text */}
            <FormField control={form.control} name="text" render={({ field }) => (
              <FormItem>
                <FormLabel>Clause Text</FormLabel>
                <FormControl>
                  <Textarea
                    placeholder="Enter the full clause wording…"
                    className="min-h-[100px] resize-y"
                    {...field}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )} />

            {/* Type */}
            <FormField control={form.control} name="type" render={({ field }) => (
              <FormItem>
                <FormLabel>Type</FormLabel>
                <Select value={field.value} onValueChange={field.onChange}>
                  <FormControl>
                    <SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {CLAUSE_TYPES.map(t => (
                      <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />

            {/* Applicability toggle */}
            <FormField control={form.control} name="applicability" render={({ field }) => (
              <FormItem>
                <FormLabel>Applicability</FormLabel>
                <div className="flex items-start gap-3 pt-1">
                  <Switch
                    checked={field.value === 'MANDATORY'}
                    onCheckedChange={(checked) => field.onChange(checked ? 'MANDATORY' : 'OPTIONAL')}
                  />
                  <div>
                    <p className="text-sm font-medium leading-none">
                      {field.value === 'MANDATORY' ? 'Mandatory' : 'Optional'}
                    </p>
                    <p className="text-xs text-muted-foreground mt-1">
                      {field.value === 'MANDATORY'
                        ? 'Auto-applied to all new policies for selected products'
                        : 'Available to add manually on individual policies'}
                    </p>
                  </div>
                </div>
                <FormMessage />
              </FormItem>
            )} />

            {/* Products multi-select */}
            <FormItem>
              <FormLabel>Products</FormLabel>
              {/* Selected chips */}
              {productIdsField.value.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mb-2">
                  {PRODUCTS.filter(p => productIdsField.value.includes(p.id)).map(p => (
                    <span
                      key={p.id}
                      className="inline-flex items-center gap-1 rounded-md bg-teal-50 px-2 py-0.5 text-xs font-medium text-teal-700"
                    >
                      {p.name}
                      <button
                        type="button"
                        onClick={() => toggleProduct(p.id)}
                        className="hover:text-teal-900"
                      >
                        ✕
                      </button>
                    </span>
                  ))}
                </div>
              )}
              {/* Checkbox list */}
              <div className="rounded-md border divide-y max-h-[160px] overflow-y-auto">
                {PRODUCTS.map(p => (
                  <label
                    key={p.id}
                    className="flex items-center gap-2.5 px-3 py-2 cursor-pointer hover:bg-secondary"
                  >
                    <Checkbox
                      checked={productIdsField.value.includes(p.id)}
                      onCheckedChange={() => toggleProduct(p.id)}
                    />
                    <span className="text-sm">{p.name}</span>
                  </label>
                ))}
              </div>
              {form.formState.errors.productIds && (
                <p className="text-sm font-medium text-destructive">
                  {form.formState.errors.productIds.message}
                </p>
              )}
            </FormItem>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit">Save Clause</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 3.2: Typecheck**

```bash
cd cia-frontend && pnpm --filter @cia/back-office typecheck
```

Expected: exit 0. Note: ClauseBankTab doesn't export `ClauseRow` yet — the import will fail. Continue to Task 4 to fix it.

---

## Task 4: ClauseBankTab

**Files:**
- Modify: `setup/pages/policy-specs/ClauseBankTab.tsx` (full implementation)

- [ ] **Step 4.1: Implement ClauseBankTab**

Replace `setup/pages/policy-specs/ClauseBankTab.tsx` with:

```tsx
import { useMemo, useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import ClauseSheet from './ClauseSheet';
import { PRODUCTS } from './ClauseSheet';

// ── Types ─────────────────────────────────────────────────────────────────────
export type ClauseType        = 'STANDARD' | 'EXCLUSION' | 'SPECIAL_CONDITION' | 'WARRANTY';
export type ClauseApplicability = 'MANDATORY' | 'OPTIONAL';

export interface ClauseRow {
  id:            string;
  title:         string;
  text:          string;
  type:          ClauseType;
  applicability: ClauseApplicability;
  productIds:    string[];
  productNames:  string[];
}

// ── Mock data ─────────────────────────────────────────────────────────────────
const INITIAL_CLAUSES: ClauseRow[] = [
  { id: 'c1', title: 'Third Party Liability',      text: 'Indemnity for third party bodily injury and property damage as per the Motor Vehicles (Third Party Insurance) Act.',         type: 'STANDARD',          applicability: 'MANDATORY', productIds: ['1','2'], productNames: ['Private Motor Comprehensive','Commercial Vehicle'] },
  { id: 'c2', title: 'Own Damage',                 text: 'Covers accidental damage to the insured vehicle including fire, theft and malicious damage.',                                type: 'STANDARD',          applicability: 'MANDATORY', productIds: ['1'],     productNames: ['Private Motor Comprehensive'] },
  { id: 'c3', title: 'Exclusion — Racing',         text: 'This policy does not cover loss or damage arising from or whilst the vehicle is used in racing, rallying or similar events.', type: 'EXCLUSION',         applicability: 'OPTIONAL',  productIds: ['1','2'], productNames: ['Private Motor Comprehensive','Commercial Vehicle'] },
  { id: 'c4', title: 'Fire — Standard Perils',     text: 'Covers loss or damage by fire, lightning, explosion, and allied perils as described in the standard fire clauses.',           type: 'STANDARD',          applicability: 'MANDATORY', productIds: ['3'],     productNames: ['Fire & Burglary Standard'] },
  { id: 'c5', title: 'Burglary & Housebreaking',   text: 'Indemnity against loss or damage resulting from burglary, housebreaking or theft involving forcible entry.',                  type: 'STANDARD',          applicability: 'MANDATORY', productIds: ['3'],     productNames: ['Fire & Burglary Standard'] },
  { id: 'c6', title: 'Exclusion — Wear & Tear',    text: 'This policy excludes damage attributable to gradual deterioration, wear and tear or inherent vice.',                          type: 'EXCLUSION',         applicability: 'OPTIONAL',  productIds: ['1','2','3','4'], productNames: ['Private Motor Comprehensive','Commercial Vehicle','Fire & Burglary Standard','Marine Cargo Open Cover'] },
  { id: 'c7', title: 'Marine — Institute Cargo',   text: 'Coverage in accordance with the Institute Cargo Clauses (A) for all risks of physical loss or damage.',                       type: 'STANDARD',          applicability: 'MANDATORY', productIds: ['4'],     productNames: ['Marine Cargo Open Cover'] },
  { id: 'c8', title: 'Warranty — Security Survey', text: 'It is warranted that a security survey be completed and recommendations implemented within 30 days of policy inception.',     type: 'WARRANTY',          applicability: 'OPTIONAL',  productIds: ['3'],     productNames: ['Fire & Burglary Standard'] },
];

const TYPE_LABELS: Record<ClauseType, string> = {
  STANDARD: 'Standard', EXCLUSION: 'Exclusion', SPECIAL_CONDITION: 'Special Condition', WARRANTY: 'Warranty',
};

// ── Component ─────────────────────────────────────────────────────────────────
export default function ClauseBankTab() {
  const [clauses,      setClauses]      = useState<ClauseRow[]>(INITIAL_CLAUSES);
  const [sheetOpen,    setSheetOpen]    = useState(false);
  const [editing,      setEditing]      = useState<ClauseRow | null>(null);
  const [deleteId,     setDeleteId]     = useState<string | null>(null);
  const [search,       setSearch]       = useState('');
  const [productFilter,setProductFilter]= useState('all');
  const [typeFilter,   setTypeFilter]   = useState('all');

  // ── Filtering ──────────────────────────────────────────────────────────────
  const filtered = useMemo(() => {
    return clauses.filter(c => {
      const matchSearch  = search === '' || c.title.toLowerCase().includes(search.toLowerCase()) || c.text.toLowerCase().includes(search.toLowerCase());
      const matchProduct = productFilter === 'all' || c.productIds.includes(productFilter);
      const matchType    = typeFilter === 'all'    || c.type === typeFilter;
      return matchSearch && matchProduct && matchType;
    });
  }, [clauses, search, productFilter, typeFilter]);

  // ── Actions ────────────────────────────────────────────────────────────────
  function openCreate() { setEditing(null); setSheetOpen(true); }
  function openEdit(c: ClauseRow) { setEditing(c); setSheetOpen(true); }

  function openDuplicate(c: ClauseRow) {
    setEditing({ ...c, id: '', title: `Copy of ${c.title}` });
    setSheetOpen(true);
  }

  function handleSave(values: ClauseSavePayload) {
    const productNames = PRODUCTS.filter(p => values.productIds.includes(p.id)).map(p => p.name);
    if (values.id) {
      setClauses(prev => prev.map(c => c.id === values.id ? { ...values, id: values.id, productNames } : c));
    } else {
      setClauses(prev => [...prev, { ...values, id: crypto.randomUUID(), productNames }]);
    }
  }

  function handleDelete() {
    if (deleteId) {
      setClauses(prev => prev.filter(c => c.id !== deleteId));
      setDeleteId(null);
    }
  }

  // ── Columns ────────────────────────────────────────────────────────────────
  const columns: ColumnDef<ClauseRow>[] = [
    {
      accessorKey: 'title',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Clause Title" />,
      cell: ({ row }) => (
        <div className="max-w-[260px]">
          <p className="font-medium text-foreground text-sm">{row.original.title}</p>
          <p className="text-xs text-muted-foreground mt-0.5 line-clamp-2 leading-relaxed">{row.original.text}</p>
        </div>
      ),
    },
    {
      accessorKey: 'productNames',
      header: 'Products',
      cell: ({ row }) => {
        const names: string[] = row.original.productNames;
        const shown = names.slice(0, 2);
        const extra = names.length - 2;
        return (
          <div className="flex flex-wrap gap-1">
            {shown.map(n => (
              <span key={n} className="rounded-md bg-teal-50 px-1.5 py-0.5 text-[10px] font-medium text-teal-700">{n}</span>
            ))}
            {extra > 0 && (
              <span className="rounded-md bg-secondary px-1.5 py-0.5 text-[10px] text-muted-foreground">+{extra}</span>
            )}
          </div>
        );
      },
    },
    {
      accessorKey: 'type',
      header: 'Type',
      cell: ({ getValue }) => (
        <span className="text-sm text-foreground">{TYPE_LABELS[getValue() as ClauseType]}</span>
      ),
    },
    {
      accessorKey: 'applicability',
      header: 'Applicability',
      cell: ({ getValue }) => {
        const v = getValue() as ClauseApplicability;
        return v === 'MANDATORY'
          ? <Badge className="text-[10px] bg-red-50 text-red-700 border-red-200 hover:bg-red-50">Mandatory</Badge>
          : <Badge className="text-[10px] bg-green-50 text-green-700 border-green-200 hover:bg-green-50">Optional</Badge>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            { label: 'Edit',      onClick: (r) => openEdit(r.original) },
            { label: 'Duplicate', onClick: (r) => openDuplicate(r.original) },
            { label: 'Delete',    onClick: (r) => setDeleteId(r.original.id) },
          ]}
        />
      ),
    },
  ];

  return (
    <>
      {/* Toolbar */}
      <div className="flex items-center gap-2 flex-wrap mb-4">
        <Input
          placeholder="Search clauses…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="h-8 w-[200px] text-sm"
        />
        <Select value={productFilter} onValueChange={setProductFilter}>
          <SelectTrigger className="h-8 w-[200px] text-sm">
            <SelectValue placeholder="All Products" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Products</SelectItem>
            {PRODUCTS.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={typeFilter} onValueChange={setTypeFilter}>
          <SelectTrigger className="h-8 w-[180px] text-sm">
            <SelectValue placeholder="All Types" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Types</SelectItem>
            <SelectItem value="STANDARD">Standard</SelectItem>
            <SelectItem value="EXCLUSION">Exclusion</SelectItem>
            <SelectItem value="SPECIAL_CONDITION">Special Condition</SelectItem>
            <SelectItem value="WARRANTY">Warranty</SelectItem>
          </SelectContent>
        </Select>
        <div className="flex-1" />
        <Button size="sm" onClick={openCreate}>+ Add Clause</Button>
      </div>

      <DataTable columns={columns} data={filtered} />

      {/* ClauseSheet */}
      <ClauseSheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        clause={editing}
        onSave={handleSave}
      />

      {/* Delete confirm dialog */}
      <Dialog open={!!deleteId} onOpenChange={() => setDeleteId(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete clause?</DialogTitle>
            <DialogDescription>This cannot be undone. The clause will be removed from the library.</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteId(null)}>Cancel</Button>
            <Button variant="destructive" onClick={handleDelete}>Delete</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
```

- [ ] **Step 4.2: Typecheck**

```bash
cd cia-frontend && pnpm --filter @cia/back-office typecheck
```

Expected: exit 0. Both ClauseBankTab and ClauseSheet should now compile cleanly (ClauseRow and PRODUCTS are exported from ClauseSheet and imported in ClauseBankTab — wait, PRODUCTS is exported from ClauseSheet and ClauseRow is exported from ClauseBankTab, and ClauseSheet imports ClauseRow from ClauseBankTab). Confirm there are no circular import issues.

> **Circular import check:** ClauseSheet imports `ClauseRow` from ClauseBankTab. ClauseBankTab imports `ClauseSheet` and `PRODUCTS` from ClauseSheet. This creates a circular dependency. Fix: move `PRODUCTS` and `ClauseRow`/`ClauseType`/`ClauseApplicability` types out of both files into a new file `clause-types.ts`.

- [ ] **Step 4.3: Resolve circular import — create clause-types.ts**

Create `setup/pages/policy-specs/clause-types.ts`:

```ts
export type ClauseType        = 'STANDARD' | 'EXCLUSION' | 'SPECIAL_CONDITION' | 'WARRANTY';
export type ClauseApplicability = 'MANDATORY' | 'OPTIONAL';

export interface ClauseRow {
  id:            string;
  title:         string;
  text:          string;
  type:          ClauseType;
  applicability: ClauseApplicability;
  productIds:    string[];
  productNames:  string[];
}

export const PRODUCTS = [
  { id: '1', name: 'Private Motor Comprehensive' },
  { id: '2', name: 'Commercial Vehicle' },
  { id: '3', name: 'Fire & Burglary Standard' },
  { id: '4', name: 'Marine Cargo Open Cover' },
] as const;

export const CLAUSE_TYPES = [
  { value: 'STANDARD' as const,          label: 'Standard' },
  { value: 'EXCLUSION' as const,         label: 'Exclusion' },
  { value: 'SPECIAL_CONDITION' as const, label: 'Special Condition' },
  { value: 'WARRANTY' as const,          label: 'Warranty' },
];

/** Shape passed from ClauseSheet.onSave to ClauseBankTab.handleSave */
export type ClauseSavePayload = Omit<ClauseRow, 'productNames' | 'id'> & { id?: string };
```

- [ ] **Step 4.4: Update imports in ClauseSheet**

In `ClauseSheet.tsx`, remove the local `PRODUCTS` and `CLAUSE_TYPES` constants and the exported `ClauseRow` type. Replace the imports at the top with:

```tsx
import type { ClauseRow, ClauseSavePayload } from './clause-types';
import { PRODUCTS, CLAUSE_TYPES } from './clause-types';
```

Remove the local declarations of `PRODUCTS`, `CLAUSE_TYPES` that were in ClauseSheet. Everything else in the file stays the same.

- [ ] **Step 4.5: Update imports in ClauseBankTab**

In `ClauseBankTab.tsx`, remove the local `ClauseRow`, `ClauseType`, `ClauseApplicability` type declarations. Replace the imports at the top with:

```tsx
import type { ClauseRow, ClauseType, ClauseApplicability, ClauseSavePayload } from './clause-types';
import { PRODUCTS } from './clause-types';
```

Remove the import of `PRODUCTS` from `./ClauseSheet`. Keep all logic the same.

- [ ] **Step 4.6: Typecheck**

```bash
cd cia-frontend && pnpm --filter @cia/back-office typecheck
```

Expected: exit 0.

- [ ] **Step 4.7: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/
git commit -m "feat(setup): Clause Bank tab with DataTable, search/filter, and ClauseSheet"
```

---

## Task 5: TemplateUploadSheet

**Files:**
- Create: `setup/pages/policy-specs/template-types.ts`
- Create: `setup/pages/policy-specs/TemplateUploadSheet.tsx`

- [ ] **Step 5.1: Create template-types.ts**

Create `setup/pages/policy-specs/template-types.ts`:

```ts
export type TemplateType =
  | 'POLICY_DOCUMENT'
  | 'CERTIFICATE'
  | 'SCHEDULE'
  | 'DEBIT_NOTE'
  | 'ENDORSEMENT'
  | 'OTHER';

export interface TemplateRow {
  id:          string;
  productId:   string;
  productName: string;
  name:        string;
  filename:    string;
  type:        TemplateType;
  status:      'ACTIVE' | 'ARCHIVED';
  uploadedAt:  string;
}

export const TEMPLATE_TYPES: { value: TemplateType; label: string }[] = [
  { value: 'POLICY_DOCUMENT', label: 'Policy Document' },
  { value: 'CERTIFICATE',     label: 'Certificate' },
  { value: 'SCHEDULE',        label: 'Schedule' },
  { value: 'DEBIT_NOTE',      label: 'Debit Note' },
  { value: 'ENDORSEMENT',     label: 'Endorsement' },
  { value: 'OTHER',           label: 'Other' },
];
```

- [ ] **Step 5.2: Create TemplateUploadSheet**

Create `setup/pages/policy-specs/TemplateUploadSheet.tsx`:

```tsx
import { useEffect, useRef, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import {
  Button,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import type { TemplateRow, TemplateType } from './template-types';
import { TEMPLATE_TYPES } from './template-types';

// ── Schema ───────────────────────────────────────────────────────────────────
const templateSchema = z.object({
  name: z.string().min(2, 'Required'),
  type: z.enum(['POLICY_DOCUMENT','CERTIFICATE','SCHEDULE','DEBIT_NOTE','ENDORSEMENT','OTHER']),
});
type TemplateFormValues = z.infer<typeof templateSchema>;

// ── Props ────────────────────────────────────────────────────────────────────
interface Props {
  open:          boolean;
  onOpenChange:  (v: boolean) => void;
  productId:     string;
  productName:   string;
  /** When replacing an existing template, pre-fills type and shows the warning banner */
  replaceTemplate?: Pick<TemplateRow, 'id' | 'type'> | null;
  onSave:        (values: TemplateFormValues & { file: File; replaceId?: string }) => void;
}

export default function TemplateUploadSheet({
  open, onOpenChange, productId, productName, replaceTemplate, onSave,
}: Props) {
  const [file,     setFile]     = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [fileError,setFileError]= useState('');
  const fileInputRef             = useRef<HTMLInputElement>(null);

  const form = useForm<TemplateFormValues>({
    resolver:      zodResolver(templateSchema) as any,
    defaultValues: { name: '', type: 'POLICY_DOCUMENT' },
  });

  useEffect(() => {
    if (!open) { setFile(null); setFileError(''); }
    form.reset({
      name: '',
      type: replaceTemplate?.type ?? 'POLICY_DOCUMENT',
    });
  }, [open, replaceTemplate]);

  function acceptFile(f: File) {
    const valid = f.name.endsWith('.docx') || f.name.endsWith('.pdf');
    if (!valid) { setFileError('Only .docx and .pdf files are accepted.'); return; }
    if (f.size > 10 * 1024 * 1024) { setFileError('File must be under 10 MB.'); return; }
    setFileError('');
    setFile(f);
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(false);
    const f = e.dataTransfer.files[0];
    if (f) acceptFile(f);
  }

  function handleFileInput(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    if (f) acceptFile(f);
  }

  function onSubmit(values: TemplateFormValues) {
    if (!file) { setFileError('Please select a file.'); return; }
    onSave({ ...values, file, replaceId: replaceTemplate?.id });
    onOpenChange(false);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-[440px] sm:max-w-[440px] overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{replaceTemplate ? 'Replace Template' : 'Upload Template'}</SheetTitle>
          <SheetDescription>
            {replaceTemplate
              ? 'Uploading a new file will archive the current version.'
              : 'Upload a .docx or .pdf master template for this product.'}
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">

            {/* Product — read-only */}
            <FormItem>
              <FormLabel>Product</FormLabel>
              <Input value={productName} readOnly className="bg-muted text-muted-foreground cursor-default" />
              <p className="text-xs text-muted-foreground mt-1">Pre-filled from the product selector.</p>
            </FormItem>

            {/* Name */}
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem>
                <FormLabel>Template Name</FormLabel>
                <FormControl><Input placeholder="e.g. Motor Comprehensive Policy v3" {...field} /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            {/* Type */}
            <FormField control={form.control} name="type" render={({ field }) => (
              <FormItem>
                <FormLabel>Template Type</FormLabel>
                <Select
                  value={field.value}
                  onValueChange={field.onChange}
                  disabled={!!replaceTemplate}
                >
                  <FormControl>
                    <SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {TEMPLATE_TYPES.map(t => (
                      <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {replaceTemplate && (
                  <p className="text-xs text-muted-foreground mt-1">Type is locked when replacing.</p>
                )}
                <FormMessage />
              </FormItem>
            )} />

            {/* File drop zone */}
            <FormItem>
              <FormLabel>File</FormLabel>
              <div
                onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
                onDragLeave={() => setDragOver(false)}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
                className={`rounded-lg border-2 border-dashed p-6 text-center cursor-pointer transition-colors ${dragOver ? 'border-primary bg-teal-50' : 'border-border hover:border-primary/50'}`}
              >
                {file ? (
                  <div>
                    <p className="text-sm font-medium text-foreground">{file.name}</p>
                    <p className="text-xs text-muted-foreground mt-1">{(file.size / 1024).toFixed(0)} KB — click to change</p>
                  </div>
                ) : (
                  <div>
                    <p className="text-sm font-medium text-foreground">Drop .docx or .pdf here</p>
                    <p className="text-xs text-muted-foreground mt-1">
                      or <span className="text-primary">browse to upload</span> · Max 10 MB
                    </p>
                  </div>
                )}
              </div>
              <input
                ref={fileInputRef}
                type="file"
                accept=".docx,.pdf"
                className="hidden"
                onChange={handleFileInput}
              />
              {fileError && <p className="text-sm font-medium text-destructive mt-1">{fileError}</p>}
            </FormItem>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit">Upload Template</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 5.3: Typecheck**

```bash
cd cia-frontend && pnpm --filter @cia/back-office typecheck
```

Expected: exit 0.

- [ ] **Step 5.4: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/
git commit -m "feat(setup): TemplateUploadSheet with drag-and-drop file zone"
```

---

## Task 6: TemplatesTab

**Files:**
- Modify: `setup/pages/policy-specs/TemplatesTab.tsx` (full implementation)

- [ ] **Step 6.1: Implement TemplatesTab**

Replace `setup/pages/policy-specs/TemplatesTab.tsx` with:

```tsx
import { useState } from 'react';
import {
  Badge,
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  cn,
} from '@cia/ui';
import type { TemplateRow, TemplateType } from './template-types';
import { TEMPLATE_TYPES } from './template-types';
import TemplateUploadSheet from './TemplateUploadSheet';

// ── Products (same list as in clause-types.ts — kept local for tab independence) ──
const PRODUCTS = [
  { id: '1', name: 'Private Motor Comprehensive' },
  { id: '2', name: 'Commercial Vehicle' },
  { id: '3', name: 'Fire & Burglary Standard' },
  { id: '4', name: 'Marine Cargo Open Cover' },
];

// ── Mock data ─────────────────────────────────────────────────────────────────
const INITIAL_TEMPLATES: TemplateRow[] = [
  { id: 't1', productId: '1', productName: 'Private Motor Comprehensive', name: 'Motor Comprehensive Policy v3', filename: 'policy_template_pmc_v3.docx',  type: 'POLICY_DOCUMENT', status: 'ACTIVE',   uploadedAt: '2026-03-15' },
  { id: 't2', productId: '1', productName: 'Private Motor Comprehensive', name: 'NAICOM Motor Certificate',      filename: 'naicom_cert_motor_v2.docx',      type: 'CERTIFICATE',     status: 'ACTIVE',   uploadedAt: '2026-01-10' },
  { id: 't3', productId: '1', productName: 'Private Motor Comprehensive', name: 'Policy Schedule',               filename: 'policy_schedule_pmc.docx',       type: 'SCHEDULE',        status: 'ARCHIVED', uploadedAt: '2025-11-02' },
];

// ── Badge styles per template type ───────────────────────────────────────────
const TYPE_BADGE: Record<TemplateType, string> = {
  POLICY_DOCUMENT: 'bg-blue-50 text-blue-700 border-blue-200',
  CERTIFICATE:     'bg-amber-50 text-amber-700 border-amber-200',
  SCHEDULE:        'bg-neutral-100 text-neutral-600 border-neutral-200',
  DEBIT_NOTE:      'bg-purple-50 text-purple-700 border-purple-200',
  ENDORSEMENT:     'bg-teal-50 text-teal-700 border-teal-200',
  OTHER:           'bg-neutral-100 text-neutral-600 border-neutral-200',
};

function typeLabelOf(type: TemplateType): string {
  return TEMPLATE_TYPES.find(t => t.value === type)?.label ?? type;
}

// ── Component ─────────────────────────────────────────────────────────────────
export default function TemplatesTab() {
  const [templates,     setTemplates]     = useState<TemplateRow[]>(INITIAL_TEMPLATES);
  const [selectedProd,  setSelectedProd]  = useState('');
  const [uploadOpen,    setUploadOpen]    = useState(false);
  const [replaceTarget, setReplaceTarget] = useState<Pick<TemplateRow,'id','type'> | null>(null);
  const [archiveId,     setArchiveId]     = useState<string | null>(null);
  const [deleteId,      setDeleteId]      = useState<string | null>(null);

  const product     = PRODUCTS.find(p => p.id === selectedProd);
  const visible     = templates.filter(t => t.productId === selectedProd);
  const activeCount = visible.filter(t => t.status === 'ACTIVE').length;

  function handleUpload(values: { name: string; type: TemplateType; file: File; replaceId?: string }) {
    const prod = product!;
    if (values.replaceId) {
      setTemplates(prev => prev.map(t =>
        t.id === values.replaceId ? { ...t, status: 'ARCHIVED' as const } : t,
      ));
    }
    setTemplates(prev => [
      ...prev,
      {
        id: crypto.randomUUID(),
        productId:   prod.id,
        productName: prod.name,
        name:        values.name,
        filename:    values.file.name,
        type:        values.type,
        status:      'ACTIVE',
        uploadedAt:  new Date().toISOString().slice(0, 10),
      },
    ]);
    setReplaceTarget(null);
  }

  function handleArchive() {
    if (archiveId) {
      setTemplates(prev => prev.map(t => t.id === archiveId ? { ...t, status: 'ARCHIVED' } : t));
      setArchiveId(null);
    }
  }

  function handleDelete() {
    if (deleteId) {
      setTemplates(prev => prev.filter(t => t.id !== deleteId));
      setDeleteId(null);
    }
  }

  const deleteTarget = templates.find(t => t.id === deleteId);

  return (
    <>
      {/* Product selector row */}
      <div className="flex items-center gap-3 flex-wrap mb-5">
        <span className="text-sm text-muted-foreground shrink-0">Product</span>
        <Select value={selectedProd} onValueChange={setSelectedProd}>
          <SelectTrigger className="w-[260px]">
            <SelectValue placeholder="Select a product…" />
          </SelectTrigger>
          <SelectContent>
            {PRODUCTS.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}
          </SelectContent>
        </Select>
        {selectedProd && (
          <span className="text-xs text-muted-foreground">
            {activeCount} active template{activeCount !== 1 ? 's' : ''}
          </span>
        )}
        <div className="flex-1" />
        {selectedProd && (
          <Button size="sm" onClick={() => { setReplaceTarget(null); setUploadOpen(true); }}>
            ↑ Upload Template
          </Button>
        )}
      </div>

      {/* Content */}
      {!selectedProd ? (
        <EmptyState title="Select a product to view its templates" />
      ) : visible.length === 0 ? (
        <EmptyState
          title="No templates yet"
          action={<Button size="sm" onClick={() => setUploadOpen(true)}>Upload Template</Button>}
        />
      ) : (
        <div className="space-y-2">
          {/* List header */}
          <div className="grid grid-cols-[2fr_1.5fr_1fr_1fr_auto] gap-3 px-3 py-1.5 rounded-md bg-muted text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
            <span>Template</span><span>Type</span><span>Uploaded</span><span>Status</span><span></span>
          </div>

          {/* Template rows */}
          {visible.map(t => (
            <div
              key={t.id}
              className={cn(
                'grid grid-cols-[2fr_1.5fr_1fr_1fr_auto] gap-3 items-center rounded-lg border px-3 py-3',
                t.status === 'ARCHIVED' && 'opacity-60',
              )}
            >
              <div>
                <p className="text-sm font-medium text-foreground">{t.name}</p>
                <p className="font-mono text-[10px] text-muted-foreground mt-0.5">{t.filename}</p>
              </div>
              <Badge className={cn('text-[10px] border w-fit hover:opacity-100', TYPE_BADGE[t.type])}>
                {typeLabelOf(t.type)}
              </Badge>
              <span className="text-sm text-foreground">{t.uploadedAt}</span>
              <Badge
                className={cn(
                  'text-[10px] border w-fit hover:opacity-100',
                  t.status === 'ACTIVE'
                    ? 'bg-green-50 text-green-700 border-green-200'
                    : 'bg-neutral-100 text-neutral-500 border-neutral-200',
                )}
              >
                {t.status === 'ACTIVE' ? 'Active' : 'Archived'}
              </Badge>
              <div className="flex items-center gap-1">
                {/* Download (mock) */}
                <Button variant="ghost" size="sm" className="h-7 w-7 p-0 text-primary" title="Download">↓</Button>
                {/* Actions */}
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="sm" className="h-7 w-7 p-0 text-muted-foreground">⋯</Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem onClick={() => { setReplaceTarget({ id: t.id, type: t.type }); setUploadOpen(true); }}>
                      Replace
                    </DropdownMenuItem>
                    {t.status === 'ACTIVE' && (
                      <DropdownMenuItem onClick={() => setArchiveId(t.id)}>Archive</DropdownMenuItem>
                    )}
                    <DropdownMenuItem
                      className="text-destructive focus:text-destructive"
                      onClick={() => setDeleteId(t.id)}
                    >
                      Delete
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Upload / Replace sheet */}
      <TemplateUploadSheet
        open={uploadOpen}
        onOpenChange={(v) => { setUploadOpen(v); if (!v) setReplaceTarget(null); }}
        productId={selectedProd}
        productName={product?.name ?? ''}
        replaceTemplate={replaceTarget}
        onSave={handleUpload}
      />

      {/* Archive confirm */}
      <Dialog open={!!archiveId} onOpenChange={() => setArchiveId(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Archive template?</DialogTitle>
            <DialogDescription>The template will be marked as archived and will no longer be used for new documents.</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setArchiveId(null)}>Cancel</Button>
            <Button onClick={handleArchive}>Archive</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete confirm */}
      <Dialog open={!!deleteId} onOpenChange={() => setDeleteId(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete template?</DialogTitle>
            <DialogDescription>
              {deleteTarget?.status === 'ACTIVE'
                ? 'Warning: this template is currently active and may be in use. This cannot be undone.'
                : 'This cannot be undone.'}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteId(null)}>Cancel</Button>
            <Button variant="destructive" onClick={handleDelete}>Delete</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
```

- [ ] **Step 6.2: Typecheck**

```bash
cd cia-frontend && pnpm --filter @cia/back-office typecheck
```

Expected: exit 0.

- [ ] **Step 6.3: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/
git commit -m "feat(setup): Templates tab with product selector, template list, and upload sheet"
```

---

## Task 7: Final verification + docs

**Files:**
- Modify: `CLAUDE.md` (Build Queue — mark Policy Specifications `[x]`)
- Modify: `cia-log.md` (append session entry)

- [ ] **Step 7.1: Full typecheck**

```bash
cd cia-frontend && pnpm --filter @cia/back-office typecheck && pnpm --filter @cia/partner typecheck
```

Expected: both exit 0.

- [ ] **Step 7.2: Manual verification checklist**

Start the dev server and verify each acceptance criterion:

```bash
cd cia-frontend && pnpm --filter @cia/back-office dev
```

Check at `http://localhost:5173/setup/policy-specifications`:

- [ ] Nav item "Policy Specifications" appears under Products in the sidebar and is active on this route
- [ ] Page loads with two tabs: Clause Bank and Templates
- [ ] Clause Bank shows 8 mock clauses with title+preview, product chips, type text, Mandatory/Optional badge
- [ ] Search bar filters clauses by title and text in real time
- [ ] Product filter narrows to clauses tagged with that product
- [ ] Type filter narrows to the selected clause type
- [ ] `+ Add Clause` opens ClauseSheet; all fields validate; saving appends a new row
- [ ] Edit (⋯ menu) opens ClauseSheet pre-filled; saving updates the row
- [ ] Duplicate creates a "Copy of …" row and opens for editing
- [ ] Delete shows confirm dialog; confirming removes the row
- [ ] Templates tab shows EmptyState (select a product) before a product is selected
- [ ] Selecting "Private Motor Comprehensive" shows 3 templates; archived row is dimmed
- [ ] Upload Template button opens TemplateUploadSheet with product pre-filled; valid file + name + type → saves and appends ACTIVE row
- [ ] File drop zone accepts .docx/.pdf, rejects other types and files > 10 MB
- [ ] Replace opens sheet with type locked and warning description; saving archives old + adds new
- [ ] Archive shows confirm dialog; archived row becomes dimmed
- [ ] Delete shows confirm dialog (with "in use" warning for active templates); confirming removes row

- [ ] **Step 7.3: Update CLAUDE.md Build Queue**

In `CLAUDE.md`, find the Policy Specifications row under Build 2 and update:

```markdown
| `[x]` | Policy Specifications | Clause bank DataTable (search, product/type filter, mandatory/optional, CRUD); template manager (per-product, type-coloured badges, upload/replace/archive/delete) |
```

Also update the Build 2 row itself to `[x]` if all sub-pages under it are now complete.

Update the **Build Progress Summary** table — Build 2 is now fully `[x]`.

- [ ] **Step 7.4: Append to cia-log.md**

Add a new entry at the bottom of `cia-log.md`:

```markdown
---

### Session 25 — Build 2 complete: Policy Specifications (Setup module)

**Files created:**
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/PolicySpecificationsPage.tsx` — page shell: PageHeader + two Tabs
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/clause-types.ts` — shared types: ClauseRow, ClauseType, ClauseApplicability, PRODUCTS, CLAUSE_TYPES
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/ClauseBankTab.tsx` — Clause Bank tab: DataTable + hand-rolled toolbar (search + product filter + type filter), 8 mock clauses, ClauseSheet for CRUD, delete confirm dialog
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/ClauseSheet.tsx` — create/edit clause drawer: react-hook-form + Zod, Switch for mandatory/optional, Checkbox list for multi-product selection
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/template-types.ts` — shared types: TemplateRow, TemplateType, TEMPLATE_TYPES
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/TemplatesTab.tsx` — Templates tab: product selector, custom card list, archive/delete/replace confirm dialogs
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/TemplateUploadSheet.tsx` — upload drawer: drag-and-drop zone, file validation (.docx/.pdf, 10 MB), Replace mode locks type field

**Files modified:**
- `cia-frontend/apps/back-office/src/modules/setup/layout/SetupLayout.tsx` — added "Policy Specifications" nav item under Products group
- `cia-frontend/apps/back-office/src/modules/setup/index.tsx` — added lazy import + route for `/setup/policy-specifications`
- `CLAUDE.md` — marked Policy Specifications `[x]`, Build 2 `[x]`, updated Build Progress Summary

**Decisions made:**
- Clause types: Standard / Exclusion / Special Condition / Warranty
- Mandatory clauses auto-apply to policies; Optional clauses are available in the picker on the Policy Detail Document tab
- Template types: Policy Document / Certificate / Schedule / Debit Note / Endorsement / Other
- Multiple templates per product are supported; each has its own type + status
- Replacing a template archives the previous version automatically
- Shared types extracted into `clause-types.ts` and `template-types.ts` to avoid circular imports between tab and sheet files
- DataTable toolbar is hand-rolled (not the built-in `toolbar` prop) because three simultaneous filters (search + product + type) need coordinated client-side state

**Open questions:** None.
```

- [ ] **Step 7.5: Final commit**

```bash
git add CLAUDE.md cia-log.md
git commit -m "docs: mark Policy Specifications complete — Build 2 fully done"
```
