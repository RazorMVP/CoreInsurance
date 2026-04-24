# Policy Specifications — Setup Module

**Date:** 2026-04-24
**Status:** Approved
**Module:** Setup & Administration (Module 1)
**Build:** Phase 2 — completes the last remaining `[ ]` sub-page

---

## Overview

Policy Specifications is the Setup sub-page where System Admins manage:

1. **Clause Bank** — a global library of all standard clauses used across products. Each clause is tagged to one or more products and marked Mandatory (auto-applied to every new policy for those products) or Optional (available to add at policy level).
2. **Templates** — per-product master `.docx`/`.pdf` document templates uploaded to object storage. The backend PDF generator (Apache PDFBox) uses these at policy approval time to produce the final policy document. Separate from the per-policy "Edit Template" available on the Policy Detail Document tab, which edits the rendered instance for a specific policy.

---

## Route & Navigation

| Item | Value |
|---|---|
| Route | `/setup/policy-specifications` |
| Nav group | **Products** (alongside Classes of Business and Products) |
| Nav label | `Policy Specifications` |
| Lazy-loaded component | `PolicySpecificationsPage` |

Changes required:
- Add nav item to `SetupLayout.tsx` under the Products group.
- Add lazy import + `<Route>` in `setup/index.tsx`.

---

## Page Structure

```
PolicySpecificationsPage
├── PageHeader (title, description)
└── Tabs
    ├── TabsTrigger: "Clause Bank"
    └── TabsTrigger: "Templates"
```

---

## Tab 1 — Clause Bank

### Layout

```
Toolbar
  ├── Search bar (flex-1, max-w-[220px])
  ├── Filter: All Products ▾  (Select)
  ├── Filter: All Types ▾     (Select)
  └── + Add Clause            (Button, opens ClauseSheet)

DataTable
  ├── Title          (title + 2-line body preview)
  ├── Products       (chip tags, up to 3 shown, +N for overflow)
  ├── Type           (plain text: Standard / Exclusion / Special Condition / Warranty)
  ├── Applicability  (Badge: Mandatory=red / Optional=green)
  └── Actions        (DataTableRowActions: Edit · Duplicate · Delete)

Pagination (page-size selector + page nav)
```

### Mock Data (8 sample clauses)

```ts
type ClauseApplicability = 'MANDATORY' | 'OPTIONAL';
type ClauseType = 'STANDARD' | 'EXCLUSION' | 'SPECIAL_CONDITION' | 'WARRANTY';

interface ClauseRow {
  id: string;
  title: string;
  text: string;
  type: ClauseType;
  applicability: ClauseApplicability;
  productIds: string[];
  productNames: string[];
}
```

Seed with at least 8 entries spanning all 4 types and both applicability values, covering Motor, Fire & Burglary, and Marine products.

### ClauseSheet (create / edit)

Right-side drawer. Fields:

| Field | Component | Validation |
|---|---|---|
| Title | Input | Required, min 2 chars |
| Clause Text | Textarea (4 rows min, resizable) | Required, min 10 chars |
| Type | Select (Standard / Exclusion / Special Condition / Warranty) | Required |
| Applicability | Toggle switch (off=Optional, on=Mandatory) + helper text | — |
| Products | Multi-select chip input — checkbox list of all products, chips shown on selection | Required, min 1 |

Applicability helper text:
- Mandatory: "Auto-applied to all new policies for selected products"
- Optional: "Available to add manually on individual policies"

Form library: `react-hook-form` + Zod. On save, append to local state (same pattern as other Setup sheets).

Row actions:
- **Edit** — opens ClauseSheet pre-filled
- **Duplicate** — creates a copy with title prefixed "Copy of …", opens sheet for editing
- **Delete** — confirm dialog ("Delete clause?" / "This cannot be undone.")

---

## Tab 2 — Templates

### Layout

```
Product selector row
  ├── Label: "Product"
  ├── Select (all active products)
  ├── Template count hint ("N templates")
  └── Upload Template button (opens TemplateUploadSheet)

Template list (custom card list, not DataTable — fewer columns, download icon prominent)
  ├── Template Name + filename (muted, font-mono)
  ├── Type badge (coloured per type)
  ├── Uploaded date
  ├── Status badge (Active=green / Archived=muted)
  ├── Download icon (↓)
  └── Row actions (⋯): Replace · Archive · Delete

EmptyState when no product selected or no templates for selected product
```

### Template Types & Badge Colours

| Type | Badge colour |
|---|---|
| POLICY_DOCUMENT | Blue (`eff6ff` / `1d4ed8`) |
| CERTIFICATE | Amber (`fef9c3` / `854d0e`) |
| SCHEDULE | Neutral (`f3f4f6` / `6b7280`) |
| DEBIT_NOTE | Purple (`f5f3ff` / `6d28d9`) |
| ENDORSEMENT | Teal (`f0fdfa` / `0f766e`) |
| OTHER | Neutral |

### Mock Data (3 templates for Private Motor Comprehensive)

```ts
type TemplateType =
  | 'POLICY_DOCUMENT'
  | 'CERTIFICATE'
  | 'SCHEDULE'
  | 'DEBIT_NOTE'
  | 'ENDORSEMENT'
  | 'OTHER';

interface TemplateRow {
  id: string;
  productId: string;
  productName: string;
  name: string;
  filename: string;
  type: TemplateType;
  status: 'ACTIVE' | 'ARCHIVED';
  uploadedAt: string;
}
```

Seed with 3 rows for Private Motor Comprehensive: one POLICY_DOCUMENT (Active), one CERTIFICATE (Active), one SCHEDULE (Archived, shown dimmed at 60% opacity).

### TemplateUploadSheet (upload)

Right-side drawer. Fields:

| Field | Component | Notes |
|---|---|---|
| Product | Input (read-only, pre-filled from selector) | Cannot be changed in sheet |
| Template Name | Input | Required |
| Template Type | Select (6 types above) | Required |
| File | Drag-and-drop zone | `.docx` or `.pdf` only · Max 10 MB · Shows filename on selection |

On upload: mock success — append to local template list, set status=ACTIVE.

Row actions:
- **Replace** — opens TemplateUploadSheet with product + type pre-filled, description: "Uploading a new file will archive the current version."
- **Archive** — confirm dialog, sets status=ARCHIVED
- **Delete** — confirm dialog, warning if status=ACTIVE ("This template is in use")

---

## Connection to Policy Detail (Document Tab)

The Setup clause bank feeds the Policy Detail Document tab (already built in Build 5):

- When a policy is created for Product X, all `MANDATORY` clauses tagged to Product X are auto-populated in the Document tab clause list.
- `OPTIONAL` clauses are available via the `+ Add Clause` button on the Document tab (shows a picker from the bank).
- The "Edit Template" button on the Document tab opens the per-policy rendered instance — not these master templates.

> This connection is wired at API level; the frontend mock data in Build 5 uses hardcoded clauses. No changes to `PolicyDetailPage.tsx` are required for this build.

---

## Files

### Create
| File | Purpose |
|---|---|
| `setup/pages/policy-specs/PolicySpecificationsPage.tsx` | Main page: PageHeader + Tabs |
| `setup/pages/policy-specs/ClauseSheet.tsx` | Create/edit clause drawer |
| `setup/pages/policy-specs/TemplateUploadSheet.tsx` | Upload template drawer |

### Modify
| File | Change |
|---|---|
| `setup/layout/SetupLayout.tsx` | Add `Policy Specifications` nav item under Products group |
| `setup/index.tsx` | Add lazy import + `<Route path="policy-specifications">` |

---

## Acceptance Criteria

- [ ] `/setup/policy-specifications` loads without errors; nav item active when on the page
- [ ] Clause Bank tab renders 8 mock clauses in DataTable with search, product filter, and type filter functional (client-side)
- [ ] `+ Add Clause` opens ClauseSheet; form validates; save appends row to table
- [ ] Edit opens sheet pre-filled; save updates row
- [ ] Duplicate creates a copy pre-filled; Delete shows confirm dialog
- [ ] Templates tab shows EmptyState before a product is selected
- [ ] Selecting "Private Motor Comprehensive" shows 3 mock templates; archived row is dimmed
- [ ] Upload Template button opens TemplateUploadSheet with product pre-filled; save appends row
- [ ] Row actions (Replace / Archive / Delete) work with confirm dialogs where required
- [ ] `pnpm --filter @cia/back-office typecheck` exits 0
