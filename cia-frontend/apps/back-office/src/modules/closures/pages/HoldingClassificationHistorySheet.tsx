import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge,
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
  Skeleton,
} from '@cia/ui';
import {
  validatedGet,
  InvestmentClassificationHistoryDtoSchema,
  type InvestmentHoldingDto,
  type InvestmentClassificationHistoryDto,
  type InvestmentClassification,
} from '@cia/api-client';
import { formatDate } from '@/lib/format';

interface ClassificationHistorySheetProps {
  holding: InvestmentHoldingDto | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const CLASSIFICATION_VARIANT: Record<InvestmentClassification, 'active' | 'pending' | 'draft' | 'rejected'> = {
  AMORTISED_COST: 'active',
  FVOCI_DEBT:     'pending',
  FVOCI_EQUITY:   'draft',
  FVPL:           'rejected',
};


export default function HoldingClassificationHistorySheet({ holding, open, onOpenChange }: ClassificationHistorySheetProps) {
  const historyQuery = useQuery<InvestmentClassificationHistoryDto[]>({
    queryKey: ['closures', 'ifrs9-classification-history', holding?.id],
    queryFn:  () => validatedGet(
      `/api/v1/finance/ifrs9/holdings/${holding!.id}/classification-history`,
      z.array(InvestmentClassificationHistoryDtoSchema),
    ),
    enabled: open && !!holding,
  });
  const history = historyQuery.data ?? [];

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full sm:max-w-2xl">
        <SheetHeader>
          <SheetTitle>Classification history</SheetTitle>
          <SheetDescription>
            {holding
              ? `${holding.securityName}${holding.isin ? ` · ISIN ${holding.isin}` : ''}`
              : 'No holding selected'}
          </SheetDescription>
        </SheetHeader>

        {holding && (
          <div className="mt-5 space-y-4">
            <div className="rounded-md border bg-card px-3 py-2.5">
              <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground mb-1.5">Current</div>
              <div className="flex items-center gap-2">
                <Badge variant={CLASSIFICATION_VARIANT[holding.classification]}>{holding.classification}</Badge>
                <span className="text-xs text-muted-foreground">
                  · acquired {formatDate(holding.acquisitionDate)}
                </span>
              </div>
              <dl className="mt-2 grid grid-cols-[10rem_1fr] gap-x-3 gap-y-1 text-xs">
                <dt className="text-muted-foreground">Asset type</dt>
                <dd className="font-mono">{holding.assetType}</dd>
                <dt className="text-muted-foreground">Status</dt>
                <dd className="font-mono">{holding.status}</dd>
                <dt className="text-muted-foreground">Acquisition cost</dt>
                <dd className="font-mono">
                  {holding.currencyCode} {holding.acquisitionCost.toLocaleString('en-GB', { minimumFractionDigits: 2 })}
                </dd>
                {holding.sppiTestPassed !== null && holding.sppiTestPassed !== undefined && (
                  <>
                    <dt className="text-muted-foreground">SPPI test (§4.1.3)</dt>
                    <dd>{holding.sppiTestPassed ? '✓ Passed' : '✗ Failed'}</dd>
                  </>
                )}
                {holding.eclStage != null && (
                  <>
                    <dt className="text-muted-foreground">ECL stage (§5.5.3)</dt>
                    <dd className="font-mono">Stage {holding.eclStage}</dd>
                  </>
                )}
              </dl>
            </div>

            <div>
              <h3 className="text-sm font-semibold mb-2">§B4.1.26 reclassification trail</h3>
              {historyQuery.isLoading && (
                <div className="space-y-2"><Skeleton className="h-16 w-full" /><Skeleton className="h-16 w-full" /></div>
              )}
              {historyQuery.isError && (
                <div className="rounded-md border border-destructive/50 bg-destructive/5 px-3 py-2 text-sm text-destructive">
                  Failed to load classification history.
                </div>
              )}
              {!historyQuery.isLoading && !historyQuery.isError && history.length === 0 && (
                <div className="rounded-md border bg-muted/40 px-3 py-6 text-center text-sm text-muted-foreground">
                  No reclassifications. Holding has stayed in <Badge variant={CLASSIFICATION_VARIANT[holding.classification]} className="ml-1">{holding.classification}</Badge> since recognition.
                </div>
              )}
              <ol className="relative space-y-3 border-l border-border pl-4">
                {history.map((row) => (
                  <li key={row.id} className="relative">
                    <span className="absolute -left-[21px] top-1 h-3 w-3 rounded-full border-2 border-background bg-muted-foreground" />
                    <div className="rounded-md border bg-card px-3 py-2.5 text-sm">
                      <div className="flex items-center gap-2 flex-wrap">
                        <Badge variant={CLASSIFICATION_VARIANT[row.previousClassification]}>{row.previousClassification}</Badge>
                        <span className="text-muted-foreground">→</span>
                        <Badge variant={CLASSIFICATION_VARIANT[row.newClassification]}>{row.newClassification}</Badge>
                        <span className="ml-auto text-xs text-muted-foreground font-mono">{formatDate(row.reclassificationDate)}</span>
                      </div>
                      <dl className="mt-2 grid grid-cols-[6rem_1fr] gap-x-2 gap-y-0.5 text-xs">
                        <dt className="text-muted-foreground">Reason</dt>
                        <dd className="italic">{row.reason}</dd>
                        <dt className="text-muted-foreground">Approved by</dt>
                        <dd>{row.approvedBy}</dd>
                      </dl>
                    </div>
                  </li>
                ))}
              </ol>
            </div>
          </div>
        )}
      </SheetContent>
    </Sheet>
  );
}
