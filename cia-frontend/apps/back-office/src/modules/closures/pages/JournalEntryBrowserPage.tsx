import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Badge,
  Button,
  Input,
  PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, StatCard,
} from '@cia/ui';
import {
  apiClient,
  apiEnvelope,
  JournalEntrySummaryDtoSchema,
  SpringPageSchema,
  type JournalEntrySummaryDto,
  type JournalEntryStatus,
} from '@cia/api-client';
import JournalEntryDetailSheet from './JournalEntryDetailSheet';

type StatusFilter = JournalEntryStatus | 'ALL';

const STATUS_VARIANT: Record<JournalEntryStatus, 'active' | 'rejected' | 'draft'> = {
  POSTED:   'active',
  REVERSED: 'rejected',
  DRAFT:    'draft',
};

function formatNGN(amount: number) {
  return `₦${amount.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export default function JournalEntryBrowserPage() {
  const [status,        setStatus]        = useState<StatusFilter>('ALL');
  const [sourceModule,  setSourceModule]  = useState<string>('');
  const [accountCode,   setAccountCode]   = useState<string>('');
  const [businessFrom,  setBusinessFrom]  = useState<string>('');
  const [businessTo,    setBusinessTo]    = useState<string>('');
  const [page,          setPage]          = useState(0);
  const pageSize = 20;

  const [detailJeId, setDetailJeId] = useState<string | null>(null);

  // Build query string from filters
  const queryString = useMemo(() => {
    const p = new URLSearchParams();
    if (status !== 'ALL')         p.set('status', status);
    if (sourceModule.trim())      p.set('sourceModule', sourceModule.trim());
    if (accountCode.trim())       p.set('accountCode', accountCode.trim());
    if (businessFrom)             p.set('businessFrom', businessFrom);
    if (businessTo)               p.set('businessTo', businessTo);
    p.set('page', page.toString());
    p.set('size', pageSize.toString());
    return p.toString();
  }, [status, sourceModule, accountCode, businessFrom, businessTo, page]);

  const listQuery = useQuery({
    queryKey: ['closures', 'journal-entries', queryString],
    queryFn:  async () => {
      const res = await apiClient.get(`/api/v1/finance/journal-entries?${queryString}`);
      return apiEnvelope(SpringPageSchema(JournalEntrySummaryDtoSchema)).parse(res.data).data;
    },
  });

  const pageData = listQuery.data;
  const entries: JournalEntrySummaryDto[] = pageData?.content ?? [];
  const totalElements = pageData?.totalElements ?? 0;
  const totalPages    = pageData?.totalPages ?? 0;

  function resetFilters() {
    setStatus('ALL');
    setSourceModule('');
    setAccountCode('');
    setBusinessFrom('');
    setBusinessTo('');
    setPage(0);
  }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Journal Entries"
        description="Every JE in the system passes through Slice 1.4's JournalEntryService gateway — subledger postings, IFRS-17 PAA engines, IFRS-9 measurements, NAICOM source data, manual posts. Idempotency triple: (source_module, source_event_type, source_reference)."
      />

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Entries (filtered)" value={totalElements.toLocaleString()} />
        <StatCard label="Page"               value={`${page + 1} / ${Math.max(totalPages, 1)}`} />
        <StatCard label="Per page"           value={pageSize.toString()} />
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Status</label>
          <Select value={status} onValueChange={(v) => { setStatus(v as StatusFilter); setPage(0); }}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">ALL</SelectItem>
              <SelectItem value="POSTED">POSTED</SelectItem>
              <SelectItem value="REVERSED">REVERSED</SelectItem>
              <SelectItem value="DRAFT">DRAFT</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Source module</label>
          <Input value={sourceModule} onChange={(e) => { setSourceModule(e.target.value); setPage(0); }} placeholder="e.g. MANUAL, POLICY" />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Account code</label>
          <Input value={accountCode} onChange={(e) => { setAccountCode(e.target.value); setPage(0); }} placeholder="e.g. 1120" />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Business from</label>
          <Input type="date" value={businessFrom} onChange={(e) => { setBusinessFrom(e.target.value); setPage(0); }} />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Business to</label>
          <Input type="date" value={businessTo} onChange={(e) => { setBusinessTo(e.target.value); setPage(0); }} />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">&nbsp;</label>
          <Button variant="outline" onClick={resetFilters} className="w-full">Reset</Button>
        </div>
      </div>

      <PageSection>
        {listQuery.isLoading ? (
          <Skeleton className="h-72 w-full rounded-lg" />
        ) : listQuery.isError ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            Failed to load journal entries.
          </div>
        ) : entries.length === 0 ? (
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            No entries match the current filters.
          </div>
        ) : (
          <table className="w-full text-sm border-collapse">
            <thead className="text-xs text-muted-foreground border-b">
              <tr>
                <th className="text-left font-medium py-2 px-2">Business date</th>
                <th className="text-left font-medium py-2 px-2">Source</th>
                <th className="text-left font-medium py-2 px-2">Reference</th>
                <th className="text-left font-medium py-2 px-2">Narrative</th>
                <th className="text-right font-medium py-2 px-2">Lines</th>
                <th className="text-right font-medium py-2 px-2">Total debit</th>
                <th className="text-left font-medium py-2 px-2">Status</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((je) => (
                <tr
                  key={je.id}
                  className="border-b last:border-0 hover:bg-secondary/40 cursor-pointer"
                  onClick={() => setDetailJeId(je.id)}
                >
                  <td className="py-2 px-2 font-mono text-xs">{je.businessDate}</td>
                  <td className="py-2 px-2">
                    <div className="font-mono text-xs">{je.sourceModule}</div>
                    <div className="text-xs text-muted-foreground">{je.sourceEventType}</div>
                  </td>
                  <td className="py-2 px-2 font-mono text-xs">{je.sourceReference}</td>
                  <td className="py-2 px-2 max-w-[20rem] truncate" title={je.narrative ?? ''}>
                    {je.narrative ?? <span className="text-muted-foreground italic">—</span>}
                  </td>
                  <td className="py-2 px-2 text-right font-mono text-xs">{je.lineCount}</td>
                  <td className="py-2 px-2 text-right font-mono text-xs">{formatNGN(je.totalDebit)}</td>
                  <td className="py-2 px-2">
                    <div className="flex items-center gap-1.5">
                      <Badge variant={STATUS_VARIANT[je.status]}>{je.status}</Badge>
                      {je.priorPeriodAdjustment && (
                        <Badge variant="outline" className="text-[10px]">PPA</Badge>
                      )}
                      {je.reversalOf && (
                        <Badge variant="outline" className="text-[10px]">REVERSAL</Badge>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </PageSection>

      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <div className="text-xs text-muted-foreground">
            {totalElements} {totalElements === 1 ? 'entry' : 'entries'} · page {page + 1} of {totalPages}
          </div>
          <div className="flex gap-2">
            <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>← Previous</Button>
            <Button size="sm" variant="outline" disabled={page >= totalPages - 1} onClick={() => setPage((p) => p + 1)}>Next →</Button>
          </div>
        </div>
      )}

      <JournalEntryDetailSheet
        jeId={detailJeId}
        open={!!detailJeId}
        onOpenChange={(open) => !open && setDetailJeId(null)}
      />
    </div>
  );
}
