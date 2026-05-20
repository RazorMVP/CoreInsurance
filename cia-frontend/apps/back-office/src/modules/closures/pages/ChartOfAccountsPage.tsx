import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge, Button, Input,
  PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, StatCard,
} from '@cia/ui';
import {
  validatedGet,
  ChartOfAccountNodeSchema,
  type ChartOfAccountNodeDto,
  type AccountType,
} from '@cia/api-client';

const TYPE_FILTERS: (AccountType | 'ALL')[] = ['ALL', 'ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'];

const TYPE_VARIANT: Record<AccountType, 'active' | 'pending' | 'draft' | 'rejected' | 'default'> = {
  ASSET:     'active',
  LIABILITY: 'pending',
  EQUITY:    'draft',
  INCOME:    'default',
  EXPENSE:   'rejected',
};

interface TreeNodeProps {
  node:          ChartOfAccountNodeDto;
  depth:         number;
  expandedCodes: Set<string>;
  toggle:        (code: string) => void;
  searchTerm:    string;
}

function highlight(text: string, term: string) {
  if (!term) return text;
  const idx = text.toLowerCase().indexOf(term.toLowerCase());
  if (idx === -1) return text;
  return (
    <>
      {text.slice(0, idx)}
      <mark className="rounded bg-amber-200 px-0.5 text-foreground">{text.slice(idx, idx + term.length)}</mark>
      {text.slice(idx + term.length)}
    </>
  );
}

function TreeNode({ node, depth, expandedCodes, toggle, searchTerm }: TreeNodeProps) {
  const hasChildren = node.children.length > 0;
  const isExpanded  = expandedCodes.has(node.code);

  return (
    <li>
      <div
        className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-secondary/60"
        style={{ paddingLeft: `${depth * 20 + 8}px` }}
      >
        {hasChildren ? (
          <button
            type="button"
            onClick={() => toggle(node.code)}
            className="flex h-5 w-5 shrink-0 items-center justify-center rounded text-muted-foreground hover:bg-secondary"
            aria-label={isExpanded ? 'Collapse' : 'Expand'}
          >
            <span className="font-mono text-xs">{isExpanded ? '▾' : '▸'}</span>
          </button>
        ) : (
          <span className="w-5 shrink-0" />
        )}
        <span className="font-mono text-xs text-muted-foreground w-24 shrink-0">{highlight(node.code, searchTerm)}</span>
        <span className="flex-1 truncate">{highlight(node.name, searchTerm)}</span>
        {depth === 0 && <Badge variant={TYPE_VARIANT[node.accountType]}>{node.accountType}</Badge>}
        {node.ifrs17Role && (
          <Badge variant="outline" className="text-[10px] font-mono">IFRS-17 · {node.ifrs17Role}</Badge>
        )}
        {node.ifrs9Role && (
          <Badge variant="outline" className="text-[10px] font-mono">IFRS-9 · {node.ifrs9Role}</Badge>
        )}
        {!node.active && (
          <Badge variant="rejected" className="text-[10px]">INACTIVE</Badge>
        )}
      </div>
      {hasChildren && isExpanded && (
        <ul>
          {node.children.map((child) => (
            <TreeNode
              key={child.code}
              node={child}
              depth={depth + 1}
              expandedCodes={expandedCodes}
              toggle={toggle}
              searchTerm={searchTerm}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

function flatten(nodes: ChartOfAccountNodeDto[]): ChartOfAccountNodeDto[] {
  const out: ChartOfAccountNodeDto[] = [];
  function walk(list: ChartOfAccountNodeDto[]) {
    for (const n of list) {
      out.push(n);
      walk(n.children);
    }
  }
  walk(nodes);
  return out;
}

function ancestorsOf(target: string, nodes: ChartOfAccountNodeDto[]): string[] {
  for (const n of nodes) {
    if (n.code === target) return [n.code];
    const sub = ancestorsOf(target, n.children);
    if (sub.length > 0) return [n.code, ...sub];
  }
  return [];
}

function filterTree(nodes: ChartOfAccountNodeDto[], type: AccountType | 'ALL'): ChartOfAccountNodeDto[] {
  if (type === 'ALL') return nodes;
  return nodes.filter((n) => n.accountType === type);
}

export default function ChartOfAccountsPage() {
  const [typeFilter,    setTypeFilter]    = useState<AccountType | 'ALL'>('ALL');
  const [searchTerm,    setSearchTerm]    = useState('');
  const [expandedCodes, setExpandedCodes] = useState<Set<string>>(new Set());

  const treeQuery = useQuery<ChartOfAccountNodeDto[]>({
    queryKey: ['closures', 'chart-of-accounts'],
    queryFn:  () => validatedGet('/api/v1/finance/chart-of-accounts', z.array(ChartOfAccountNodeSchema)),
  });
  const tree = treeQuery.data ?? [];

  const filteredTree = useMemo(() => filterTree(tree, typeFilter), [tree, typeFilter]);

  const flat = useMemo(() => flatten(tree), [tree]);
  const counts = useMemo(() => {
    return flat.reduce(
      (acc, n) => {
        acc[n.accountType] = (acc[n.accountType] ?? 0) + 1;
        acc.total += 1;
        return acc;
      },
      { total: 0, ASSET: 0, LIABILITY: 0, EQUITY: 0, INCOME: 0, EXPENSE: 0 } as Record<string, number>,
    );
  }, [flat]);

  // When a search term is entered, auto-expand all ancestors of matching nodes.
  const matchedCodes = useMemo(() => {
    if (!searchTerm) return [];
    const term = searchTerm.toLowerCase();
    return flat
      .filter((n) => n.code.toLowerCase().includes(term) || n.name.toLowerCase().includes(term))
      .map((n) => n.code);
  }, [flat, searchTerm]);

  const effectiveExpanded = useMemo(() => {
    if (!searchTerm) return expandedCodes;
    const next = new Set(expandedCodes);
    for (const code of matchedCodes) {
      for (const ancestor of ancestorsOf(code, tree)) {
        next.add(ancestor);
      }
    }
    return next;
  }, [expandedCodes, matchedCodes, searchTerm, tree]);

  function toggle(code: string) {
    setExpandedCodes((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });
  }

  function expandAll() {
    setExpandedCodes(new Set(flat.map((n) => n.code)));
  }
  function collapseAll() {
    setExpandedCodes(new Set());
  }

  if (treeQuery.isLoading) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-4 w-96" />
        <Skeleton className="h-96 w-full rounded-lg" />
      </div>
    );
  }

  if (treeQuery.isError) {
    return (
      <div className="p-6 space-y-5">
        <PageHeader title="Chart of Accounts" description="Read-only view of the per-tenant Chart of Accounts." />
        <div className="rounded-md border border-destructive/50 bg-destructive/5 px-4 py-3 text-sm text-destructive">
          Failed to load the Chart of Accounts.
        </div>
      </div>
    );
  }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Chart of Accounts"
        description="Read-only view of the per-tenant COA. Seeded by Flyway V32 (129 rows, 3-level hierarchy). IFRS-17 + IFRS-9 role tags drive the Phase 2 / Phase 3 measurement engines. CRUD is deferred until the post-Phase-7 tenant-customisation epic."
      />

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-6">
        <StatCard label="Total"     value={counts.total.toString()} />
        <StatCard label="Asset"     value={counts.ASSET.toString()} />
        <StatCard label="Liability" value={counts.LIABILITY.toString()} />
        <StatCard label="Equity"    value={counts.EQUITY.toString()} />
        <StatCard label="Income"    value={counts.INCOME.toString()} />
        <StatCard label="Expense"   value={counts.EXPENSE.toString()} />
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Account type</label>
          <Select value={typeFilter} onValueChange={(v) => setTypeFilter(v as AccountType | 'ALL')}>
            <SelectTrigger className="w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {TYPE_FILTERS.map((t) => (
                <SelectItem key={t} value={t}>{t}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1 flex-1 min-w-[12rem] max-w-md">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Search</label>
          <Input
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search code or name…"
          />
        </div>
        <div className="flex gap-1.5">
          <Button size="sm" variant="outline" onClick={expandAll}>Expand all</Button>
          <Button size="sm" variant="outline" onClick={collapseAll}>Collapse all</Button>
        </div>
      </div>

      <PageSection>
        {filteredTree.length === 0 ? (
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            No accounts match the current filter.
          </div>
        ) : (
          <ul className="divide-y">
            {filteredTree.map((node) => (
              <TreeNode
                key={node.code}
                node={node}
                depth={0}
                expandedCodes={effectiveExpanded}
                toggle={toggle}
                searchTerm={searchTerm}
              />
            ))}
          </ul>
        )}
      </PageSection>
    </div>
  );
}
