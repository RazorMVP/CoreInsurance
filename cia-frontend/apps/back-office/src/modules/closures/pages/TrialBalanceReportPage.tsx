import { Fragment, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Badge,
  Button,
  Input,
  PageHeader, PageSection,
  Skeleton, StatCard,
} from '@cia/ui';
import {
  validatedGet,
  TrialBalanceDtoSchema,
  type TrialBalanceDto,
  type TrialBalanceLineDto,
  type AccountType,
} from '@cia/api-client';

const ACCOUNT_TYPE_ORDER: AccountType[] = ['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'];

const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  ASSET:     'Assets',
  LIABILITY: 'Liabilities',
  EQUITY:    'Equity',
  INCOME:    'Income',
  EXPENSE:   'Expenses',
};

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function formatBalance(amount: number) {
  if (amount === 0) return '';
  return `₦${amount.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatInstant(iso: string) {
  return new Date(iso).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });
}

interface GroupSummary {
  type:        AccountType;
  lines:       TrialBalanceLineDto[];
  debitTotal:  number;
  creditTotal: number;
}

function group(lines: TrialBalanceLineDto[]): GroupSummary[] {
  const map = new Map<AccountType, TrialBalanceLineDto[]>();
  for (const l of lines) {
    if (!map.has(l.accountType)) map.set(l.accountType, []);
    map.get(l.accountType)!.push(l);
  }
  const out: GroupSummary[] = [];
  for (const type of ACCOUNT_TYPE_ORDER) {
    const ls = map.get(type) ?? [];
    if (ls.length === 0) continue;
    ls.sort((a, b) => a.accountCode.localeCompare(b.accountCode));
    out.push({
      type,
      lines: ls,
      debitTotal:  ls.reduce((s, l) => s + l.debitBalance,  0),
      creditTotal: ls.reduce((s, l) => s + l.creditBalance, 0),
    });
  }
  return out;
}

export default function TrialBalanceReportPage() {
  const [asOf, setAsOf] = useState<string>(today());
  const [draftAsOf, setDraftAsOf] = useState<string>(today());

  const tbQuery = useQuery<TrialBalanceDto>({
    queryKey: ['closures', 'trial-balance', asOf],
    queryFn:  () => validatedGet(`/api/v1/finance/trial-balance?asOf=${asOf}`, TrialBalanceDtoSchema),
  });
  const tb = tbQuery.data;

  const groups = useMemo(() => tb ? group(tb.lines) : [], [tb]);

  // Netted column totals — sum each account's netted dr/cr balance. These
  // are the numbers that should match in a standard trial-balance
  // presentation (Σ dr_balance == Σ cr_balance per the double-entry
  // invariant). The backend's footer.totalDebits / totalCredits are gross
  // line sums, useful as a sanity metric but not what users compare against
  // the column subtotals.
  const netTotals = useMemo(() => {
    if (!tb) return { debits: 0, credits: 0, balanced: true };
    const debits  = tb.lines.reduce((s, l) => s + l.debitBalance,  0);
    const credits = tb.lines.reduce((s, l) => s + l.creditBalance, 0);
    return { debits, credits, balanced: Math.abs(debits - credits) < 0.005 };
  }, [tb]);

  function runReport() {
    if (draftAsOf) setAsOf(draftAsOf);
  }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Trial Balance"
        description="Cumulative-since-inception balance at a chosen business date. Aggregates every JE with business_date ≤ asOf through the Slice 1.4 gateway. Source of truth for NAICOM's N02 BalanceSheetEngine + N03 PrudentialReturnEngine and for the Slice 1.9 reconciliation gate."
      />

      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <label htmlFor="tb-asof" className="text-xs font-medium uppercase tracking-wide text-muted-foreground">As of</label>
          <Input
            id="tb-asof"
            type="date"
            value={draftAsOf}
            onChange={(e) => setDraftAsOf(e.target.value)}
            className="w-48"
          />
        </div>
        <Button onClick={runReport} disabled={!draftAsOf || draftAsOf === asOf || tbQuery.isFetching}>
          {tbQuery.isFetching ? 'Running…' : 'Run report'}
        </Button>
        {tb && (
          <div className="ml-auto text-xs text-muted-foreground">
            Generated {formatInstant(tb.generatedAt)}
          </div>
        )}
      </div>

      {tb && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatCard label="Total debits"  value={formatBalance(netTotals.debits)  || '₦0.00'} />
          <StatCard label="Total credits" value={formatBalance(netTotals.credits) || '₦0.00'} />
          <StatCard label="Accounts"      value={tb.lines.length.toLocaleString()} />
          <StatCard
            label="Balance status"
            value={netTotals.balanced ? '✓ Balanced' : '⚠ Out of balance'}
          />
        </div>
      )}

      <PageSection>
        {tbQuery.isLoading ? (
          <Skeleton className="h-72 w-full rounded-lg" />
        ) : tbQuery.isError ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            Failed to load trial balance.
          </div>
        ) : groups.length === 0 ? (
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            No journal entries on or before {asOf}.
          </div>
        ) : (
          <table className="w-full text-sm border-collapse">
            <thead className="text-xs text-muted-foreground border-b">
              <tr>
                <th className="text-left font-medium py-2 px-2 w-24">Code</th>
                <th className="text-left font-medium py-2 px-2">Account</th>
                <th className="text-right font-medium py-2 px-2 w-40">Debit</th>
                <th className="text-right font-medium py-2 px-2 w-40">Credit</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((g) => (
                <Fragment key={g.type}>
                  <tr className="bg-secondary/40 border-y">
                    <td colSpan={4} className="py-1.5 px-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      {ACCOUNT_TYPE_LABEL[g.type]} <span className="text-muted-foreground/70 normal-case font-normal">({g.lines.length} {g.lines.length === 1 ? 'account' : 'accounts'})</span>
                    </td>
                  </tr>
                  {g.lines.map((l) => (
                    <tr key={l.accountId} className="border-b last:border-0">
                      <td className="py-1.5 px-2 font-mono text-xs">{l.accountCode}</td>
                      <td className="py-1.5 px-2">{l.accountName}</td>
                      <td className="py-1.5 px-2 text-right font-mono text-xs">{formatBalance(l.debitBalance)}</td>
                      <td className="py-1.5 px-2 text-right font-mono text-xs">{formatBalance(l.creditBalance)}</td>
                    </tr>
                  ))}
                  <tr className="border-b">
                    <td colSpan={2} className="py-1.5 px-2 text-xs text-muted-foreground text-right italic">
                      Subtotal — {ACCOUNT_TYPE_LABEL[g.type]}
                    </td>
                    <td className="py-1.5 px-2 text-right font-mono text-xs font-semibold">{formatBalance(g.debitTotal)}</td>
                    <td className="py-1.5 px-2 text-right font-mono text-xs font-semibold">{formatBalance(g.creditTotal)}</td>
                  </tr>
                </Fragment>
              ))}
            </tbody>
            {tb && (
              <tfoot>
                <tr className="border-t-2 border-foreground/20">
                  <td colSpan={2} className="py-2 px-2 text-sm font-semibold">Total</td>
                  <td className="py-2 px-2 text-right font-mono text-sm font-semibold">{formatBalance(netTotals.debits)  || '₦0.00'}</td>
                  <td className="py-2 px-2 text-right font-mono text-sm font-semibold">{formatBalance(netTotals.credits) || '₦0.00'}</td>
                </tr>
                <tr>
                  <td colSpan={4} className="py-2 px-2 text-right">
                    {netTotals.balanced
                      ? <Badge variant="active">Balanced — Σ dr = Σ cr</Badge>
                      : <Badge variant="rejected">Out of balance</Badge>}
                  </td>
                </tr>
                <tr className="text-xs text-muted-foreground">
                  <td colSpan={4} className="py-1 px-2 text-right italic">
                    Backed by {tb.footer.lineCount} JE {tb.footer.lineCount === 1 ? 'line' : 'lines'} · gross activity ₦{tb.footer.totalDebits.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                  </td>
                </tr>
              </tfoot>
            )}
          </table>
        )}
      </PageSection>
    </div>
  );
}
