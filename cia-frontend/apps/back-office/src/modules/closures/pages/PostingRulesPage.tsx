import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge,
  PageHeader, PageSection,
  Skeleton, StatCard,
} from '@cia/ui';
import {
  validatedGet,
  PostingRuleDtoSchema,
  type PostingRuleDto,
} from '@cia/api-client';

function formatEventType(eventType: string) {
  return eventType
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

export default function PostingRulesPage() {
  const rulesQuery = useQuery<PostingRuleDto[]>({
    queryKey: ['closures', 'posting-rules'],
    queryFn:  () => validatedGet('/api/v1/finance/posting-rules', z.array(PostingRuleDtoSchema)),
  });
  const rules = rulesQuery.data ?? [];

  const counts = useMemo(() => {
    return {
      total:  rules.length,
      active: rules.filter((r) => r.active).length,
    };
  }, [rules]);

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Posting Rules"
        description="Read-only view of the sub-ledger → journal-entry posting rules used by SubledgerPostingService. Each row maps an event type (POLICY_APPROVED, CLAIM_APPROVED, …) to its Dr/Cr COA codes plus a narrative template. Seeded by V33; SYSTEM rules are immutable until the post-Phase-7 tenant-customisation epic."
      />

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <StatCard label="Rules"                    value={counts.total.toString()} />
        <StatCard label="Active"                   value={counts.active.toString()} />
        <StatCard label="Compound (hard-coded)"    value="1"
                  sub="FAC_PREMIUM_CEDED" />
      </div>

      <PageSection>
        {rulesQuery.isLoading ? (
          <Skeleton className="h-72 w-full rounded-lg" />
        ) : rulesQuery.isError ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            Failed to load posting rules.
          </div>
        ) : rules.length === 0 ? (
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            No posting rules seeded. Expected V33 to have populated 6 rows — check Flyway state.
          </div>
        ) : (
          <table className="w-full text-sm border-collapse">
            <thead className="text-xs text-muted-foreground border-b">
              <tr>
                <th className="text-left font-medium py-2 px-2">Event type</th>
                <th className="text-left font-medium py-2 px-2">Debit (Dr)</th>
                <th className="text-left font-medium py-2 px-2">Credit (Cr)</th>
                <th className="text-left font-medium py-2 px-2">Narrative template</th>
                <th className="text-center font-medium py-2 px-2">Status</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.id} className="border-b last:border-0 align-top">
                  <td className="py-2 px-2">
                    <div className="font-mono text-xs">{r.sourceEventType}</div>
                    <div className="text-xs text-muted-foreground">{formatEventType(r.sourceEventType)}</div>
                  </td>
                  <td className="py-2 px-2">
                    <div className="font-mono text-xs">{r.debitAccountCode}</div>
                    <div className="text-xs text-muted-foreground">{r.debitAccountName}</div>
                  </td>
                  <td className="py-2 px-2">
                    <div className="font-mono text-xs">{r.creditAccountCode}</div>
                    <div className="text-xs text-muted-foreground">{r.creditAccountName}</div>
                  </td>
                  <td className="py-2 px-2">
                    {r.narrativeTemplate
                      ? <code className="font-mono text-[11px] text-muted-foreground">{r.narrativeTemplate}</code>
                      : <span className="text-xs text-muted-foreground italic">—</span>}
                  </td>
                  <td className="py-2 px-2 text-center">
                    <Badge variant={r.active ? 'active' : 'rejected'}>
                      {r.active ? 'ACTIVE' : 'INACTIVE'}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </PageSection>

      <div className="rounded-md border bg-muted/30 px-4 py-3 text-xs text-muted-foreground">
        <p className="font-medium text-foreground mb-1">Why is FAC_PREMIUM_CEDED missing?</p>
        <p>
          The <code className="font-mono">posting_rule</code> table is shaped for simple 2-line (1 Dr + 1 Cr) postings.
          <code className="font-mono"> FAC_PREMIUM_CEDED</code> is a compound 3-line event (Premium receivable Dr, Reinsurer payable Cr,
          RI commission Dr) and is hard-coded in <code className="font-mono">SubledgerPostingService</code> instead. Editing it
          requires a code change, not a data change.
        </p>
      </div>
    </div>
  );
}
