import { useCallback, useMemo, useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type ClauseDto, type ProductDto } from '@cia/api-client';
import type { ClauseRow, ClauseType, ClauseApplicability } from './clause-types';
import { CLAUSE_TYPES } from './clause-types';
import { useDeleteWithReason } from '@/lib/use-delete-with-reason';
import ClauseSheet from './ClauseSheet';

const TYPE_LABELS: Record<ClauseType, string> = {
  STANDARD: 'Standard', EXCLUSION: 'Exclusion', SPECIAL_CONDITION: 'Special Condition', WARRANTY: 'Warranty',
};

export default function ClauseBankTab() {
  const [sheetOpen,     setSheetOpen]     = useState(false);
  const [editing,       setEditing]       = useState<ClauseDto | null>(null);
  const [search,        setSearch]        = useState('');
  const [productFilter, setProductFilter] = useState('all');
  const [typeFilter,    setTypeFilter]    = useState('all');

  const clausesQuery = useQuery<ClauseDto[]>({
    queryKey: ['setup', 'clauses'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ClauseDto[] }>('/api/v1/setup/clauses');
      return res.data.data;
    },
  });
  const productsQuery = useQuery<ProductDto[]>({
    queryKey: ['setup', 'products'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ProductDto[] }>('/api/v1/setup/products');
      return res.data.data;
    },
  });

  const products = useMemo(() => productsQuery.data ?? [], [productsQuery.data]);
  const productNameById = useMemo(
    () => new Map(products.map(p => [p.id, p.name])),
    [products],
  );

  // ClauseDto[] → ClauseRow[] (derive productNames from the live products list).
  const rows: ClauseRow[] = useMemo(
    () => (clausesQuery.data ?? []).map(c => ({
      id:            c.id,
      title:         c.title,
      text:          c.text,
      type:          c.type,
      applicability: c.applicability,
      productIds:    c.productIds,
      productNames:  c.productIds.map(id => productNameById.get(id)).filter((n): n is string => !!n),
    })),
    [clausesQuery.data, productNameById],
  );

  const { setTarget: setDeleteTarget, dialog: deleteDialog } = useDeleteWithReason<ClauseRow>({
    endpoint: (id) => `/api/v1/setup/clauses/${id}`,
    invalidateKey: ['setup', 'clauses'],
    entityLabel: 'Clause',
    entityName: (c) => c.title,
  });

  const filtered = useMemo(() => rows.filter(c => {
    const matchSearch  = search === '' || c.title.toLowerCase().includes(search.toLowerCase()) || c.text.toLowerCase().includes(search.toLowerCase());
    const matchProduct = productFilter === 'all' || c.productIds.includes(productFilter);
    const matchType    = typeFilter === 'all'    || c.type === typeFilter;
    return matchSearch && matchProduct && matchType;
  }), [rows, search, productFilter, typeFilter]);

  function openCreate() { setEditing(null); setSheetOpen(true); }
  const openEdit = useCallback((c: ClauseRow) => {
    setEditing((clausesQuery.data ?? []).find(d => d.id === c.id) ?? null);
    setSheetOpen(true);
  }, [clausesQuery.data]);

  const columns: ColumnDef<ClauseRow>[] = useMemo(() => [
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
        if (names.length === 0) return <span className="text-xs text-muted-foreground">All products</span>;
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
      cell: ({ getValue }) => <span className="text-sm text-foreground">{TYPE_LABELS[getValue() as ClauseType]}</span>,
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
            { label: 'Edit',   onClick: (r) => openEdit(r.original) },
            { label: 'Delete', onClick: (r) => setDeleteTarget(r.original), separator: true, className: 'text-destructive' },
          ]}
        />
      ),
    },
  ], [openEdit, setDeleteTarget]);

  return (
    <>
      <div className="flex items-center gap-2 flex-wrap mb-4">
        <Input placeholder="Search clauses…" value={search} onChange={(e) => setSearch(e.target.value)} className="h-8 w-[200px] text-sm" />
        <Select value={productFilter} onValueChange={setProductFilter}>
          <SelectTrigger className="h-8 w-[200px] text-sm"><SelectValue placeholder="All Products" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Products</SelectItem>
            {products.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={typeFilter} onValueChange={setTypeFilter}>
          <SelectTrigger className="h-8 w-[180px] text-sm"><SelectValue placeholder="All Types" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Types</SelectItem>
            {CLAUSE_TYPES.map(t => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}
          </SelectContent>
        </Select>
        <div className="flex-1" />
        <Button size="sm" onClick={openCreate}>+ Add Clause</Button>
      </div>

      {clausesQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No clauses yet" description="The clause bank supplies the policy wording attached to quotes and policies. Add your first clause." />
      ) : (
        <DataTable columns={columns} data={filtered} />
      )}

      <ClauseSheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        clause={editing}
        products={products}
        onSuccess={() => setSheetOpen(false)}
      />
      {deleteDialog}
    </>
  );
}
