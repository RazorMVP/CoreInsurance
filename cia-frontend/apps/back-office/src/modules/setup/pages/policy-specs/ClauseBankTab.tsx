import { useMemo, useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { ClauseRow, ClauseType, ClauseApplicability, ClauseSavePayload } from './clause-types';
import { PRODUCTS } from './clause-types';
import ClauseSheet from './ClauseSheet';

// ── Mock data ─────────────────────────────────────────────────────────────────
const INITIAL_CLAUSES: ClauseRow[] = [
  { id: 'c1', title: 'Third Party Liability',      text: 'Indemnity for third party bodily injury and property damage as per the Motor Vehicles (Third Party Insurance) Act.',         type: 'STANDARD',          applicability: 'MANDATORY', productIds: ['1','2'], productNames: ['Private Motor Comprehensive','Commercial Vehicle'] },
  { id: 'c2', title: 'Own Damage',                 text: 'Covers accidental damage to the insured vehicle including fire, theft and malicious damage.',                                type: 'STANDARD',          applicability: 'MANDATORY', productIds: ['1'],     productNames: ['Private Motor Comprehensive'] },
  { id: 'c3', title: 'Exclusion — Racing',         text: 'This policy does not cover loss or damage arising from or whilst the vehicle is used in racing, rallying or similar events.', type: 'EXCLUSION',         applicability: 'OPTIONAL',  productIds: ['1','2'], productNames: ['Private Motor Comprehensive','Commercial Vehicle'] },
  { id: 'c4', title: 'Special Condition — Alarm System', text: 'It is a special condition of this policy that a NSIA-approved burglar alarm system is installed and in full operation throughout the period of insurance.', type: 'SPECIAL_CONDITION', applicability: 'OPTIONAL',  productIds: ['3'],     productNames: ['Fire & Burglary Standard'] },
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
  const [clauses,       setClauses]       = useState<ClauseRow[]>(INITIAL_CLAUSES);
  const [sheetOpen,     setSheetOpen]     = useState(false);
  const [editing,       setEditing]       = useState<ClauseRow | null>(null);
  const [deleteId,      setDeleteId]      = useState<string | null>(null);
  const [search,        setSearch]        = useState('');
  const [productFilter, setProductFilter] = useState('all');
  const [typeFilter,    setTypeFilter]    = useState('all');

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
    const productNames = ([...PRODUCTS] as { id: string; name: string }[]).filter(p => values.productIds.includes(p.id)).map(p => p.name);
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
            {([...PRODUCTS] as { id: string; name: string }[]).map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}
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
