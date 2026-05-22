import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader, Separator,
  Skeleton,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import {
  apiClient,
  type AdjustmentEntryDto,
  type QuoteDto,
  type QuoteRiskDto,
} from '@cia/api-client';
import QuotePdfPreview, {
  type QuotePdfData, type AdjustmentLine, type RiskItemData,
  computeQuoteSummary,
} from '../QuotePdfPreview';
import { INITIAL_CLAUSES } from '../clauses-shared';
import {
  MOCK_DISCOUNT_TYPES, MOCK_LOADING_TYPES, MOCK_QUOTE_CONFIG,
} from '../../../setup/pages/policy-specs/quote-config-types';

// ── Mock fallback (allow-mock: synthetic placeholders for /quotes/{id} when the
//    backend doesn't have data for an id; same allow-mock pattern as
//    CustomerDetailPage uses for its synthetic samples) ─────────────────────
const MOCK_QUOTES: QuoteDto[] = [
  {
    id: 'q1', quoteNumber: 'QUO-2026-00001', status: 'APPROVED',
    customerId: 'c1', customerName: 'Chioma Okafor',
    productId: 'p1', productName: 'Private Motor Comprehensive', productCode: 'PMC', productRate: 2.25,
    classOfBusinessId: 'cob-motor', classOfBusinessName: 'Motor (Private)',
    businessType: 'DIRECT',
    policyStartDate: '2026-02-01', policyEndDate: '2027-02-01',
    totalSumInsured: 3_500_000, totalGrossPremium: 78_750, totalNetPremium: 80_775,
    quoteLoadings: [], quoteDiscounts: [],
    selectedClauseIds: ['c1', 'c2'],
    inputterName: 'Chidi Okafor', approverName: 'Adeola Bello',
    risks: [
      {
        id: 'r1', description: '2022 Toyota Camry, Reg: LND-001-AA',
        sumInsured: 3_500_000, rate: 2.25, grossPremium: 78_750, premium: 80_775, orderNo: 1,
        loadings:  [{ typeId: 'l1', typeName: '', format: 'PERCENT', value: 5,   computedAmount: 3_937.50 }],
        discounts: [{ typeId: 'd1', typeName: '', format: 'PERCENT', value: 2.5, computedAmount: 2_066.41 }],
      },
    ],
    coinsuranceParticipants: [],
    createdAt: '2026-01-28T09:00:00Z', updatedAt: '2026-01-30T11:00:00Z',
  },
  {
    id: 'q2', quoteNumber: 'QUO-2026-00002', status: 'SUBMITTED',
    customerId: 'c2', customerName: 'Alaba Trading Co.',
    productId: 'p3', productName: 'Fire & Burglary Standard', productCode: 'FB', productRate: 0.80,
    classOfBusinessId: 'cob-fire', classOfBusinessName: 'Fire & Burglary',
    businessType: 'DIRECT',
    policyStartDate: '2026-03-01', policyEndDate: '2027-03-01',
    totalSumInsured: 15_000_000, totalGrossPremium: 120_000, totalNetPremium: 117_500,
    quoteLoadings:  [{ typeId: 'l1', typeName: '', format: 'PERCENT', value: 2.5, computedAmount: 3_000 }],
    quoteDiscounts: [{ typeId: 'd4', typeName: '', format: 'FLAT',    value: 10_000, computedAmount: 10_000 }],
    selectedClauseIds: ['c5', 'c6', 'c8'],
    inputterName: 'Chidi Okafor', approverName: '',
    risks: [
      {
        id: 'r2', description: 'Mixed stock — Eko Hotel Annexe, Warehouse B',
        sumInsured: 10_000_000, rate: 0.80, grossPremium: 80_000, premium: 83_600, orderNo: 1,
        loadings:  [{ typeId: 'l2', typeName: '', format: 'PERCENT', value: 10, computedAmount: 8_000 }],
        discounts: [{ typeId: 'd3', typeName: '', format: 'FLAT',    value: 5_000, computedAmount: 5_000 }],
      },
      {
        id: 'r3', description: 'Fixtures & fittings — Warehouse B',
        sumInsured: 5_000_000, rate: 0.80, grossPremium: 40_000, premium: 38_000, orderNo: 2,
        loadings:  [],
        discounts: [{ typeId: 'd2', typeName: '', format: 'PERCENT', value: 5, computedAmount: 2_000 }],
      },
    ],
    coinsuranceParticipants: [],
    createdAt: '2026-02-01T09:00:00Z', updatedAt: '2026-02-05T15:00:00Z',
  },
  {
    id: 'q3', quoteNumber: 'QUO-2026-00003', status: 'DRAFT',
    customerId: 'c3', customerName: 'Emeka Eze',
    productId: 'p1', productName: 'Private Motor Comprehensive', productCode: 'PMC', productRate: 2.25,
    classOfBusinessId: 'cob-motor', classOfBusinessName: 'Motor (Private)',
    businessType: 'DIRECT',
    policyStartDate: '2026-03-15', policyEndDate: '2027-03-15',
    totalSumInsured: 2_200_000, totalGrossPremium: 49_500, totalNetPremium: 49_500,
    quoteLoadings: [], quoteDiscounts: [], selectedClauseIds: [],
    inputterName: 'Chidi Okafor', approverName: '',
    risks: [
      {
        id: 'r4', description: '2020 Honda Accord, Reg: ABJ-222-XY',
        sumInsured: 2_200_000, rate: 2.25, grossPremium: 49_500, premium: 49_500, orderNo: 1,
        loadings: [], discounts: [],
      },
    ],
    coinsuranceParticipants: [],
    createdAt: '2026-02-10T09:00:00Z', updatedAt: '2026-02-10T09:00:00Z',
  },
  {
    id: 'q4', quoteNumber: 'QUO-2026-00004', status: 'CONVERTED',
    customerId: 'c1', customerName: 'Chioma Okafor',
    productId: 'p4', productName: 'Marine Cargo Open Cover', productCode: 'MCO', productRate: 0.75,
    classOfBusinessId: 'cob-marine', classOfBusinessName: 'Marine Cargo',
    businessType: 'DIRECT',
    policyStartDate: '2026-01-15', policyEndDate: '2027-01-15',
    totalSumInsured: 8_000_000, totalGrossPremium: 60_000, totalNetPremium: 60_000,
    quoteLoadings: [], quoteDiscounts: [], selectedClauseIds: ['c7'],
    inputterName: 'Chidi Okafor', approverName: 'Adeola Bello',
    risks: [
      {
        id: 'r5', description: 'General cargo — Lagos to Kano open cover',
        sumInsured: 8_000_000, rate: 0.75, grossPremium: 60_000, premium: 60_000, orderNo: 1,
        loadings: [], discounts: [],
      },
    ],
    coinsuranceParticipants: [],
    createdAt: '2026-01-10T09:00:00Z', updatedAt: '2026-01-15T14:00:00Z',
  },
  {
    id: 'q5', quoteNumber: 'QUO-2026-00005', status: 'REJECTED',
    customerId: 'c5', customerName: 'Ngozi Adeyemi',
    productId: 'p1', productName: 'Private Motor Comprehensive', productCode: 'PMC', productRate: 2.25,
    classOfBusinessId: 'cob-motor', classOfBusinessName: 'Motor (Private)',
    businessType: 'DIRECT',
    policyStartDate: '2026-02-20', policyEndDate: '2027-02-20',
    totalSumInsured: 4_000_000, totalGrossPremium: 90_000, totalNetPremium: 90_000,
    quoteLoadings: [], quoteDiscounts: [], selectedClauseIds: [],
    inputterName: 'Chidi Okafor', approverName: '',
    risks: [
      {
        id: 'r6', description: '2019 Mercedes GLE 450, Reg: LND-999-ZZ',
        sumInsured: 4_000_000, rate: 2.25, grossPremium: 90_000, premium: 90_000, orderNo: 1,
        loadings: [], discounts: [],
      },
    ],
    coinsuranceParticipants: [],
    createdAt: '2026-02-08T09:00:00Z', updatedAt: '2026-02-12T16:00:00Z',
  },
];

// ── Helpers ───────────────────────────────────────────────────────────────────
const statusVariant: Record<string, 'active' | 'pending' | 'rejected' | 'draft' | 'cancelled'> = {
  APPROVED: 'active', SUBMITTED: 'pending', DRAFT: 'draft', CONVERTED: 'active', REJECTED: 'rejected', EXPIRED: 'cancelled',
};

function fmt(n: number) {
  return `₦${n.toLocaleString(undefined, { minimumFractionDigits: 2 })}`;
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2.5" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-44 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

function resolveTypeName(typeId: string, category: 'loading' | 'discount') {
  const list = category === 'loading' ? MOCK_LOADING_TYPES : MOCK_DISCOUNT_TYPES;
  return list.find(t => t.id === typeId)?.name ?? typeId;
}

/**
 * Convert a backend AdjustmentEntryDto to the PDF preview's AdjustmentLine.
 * The PDF preview computes amounts itself from (format, value) + the gross
 * base, so we drop `computedAmount` here. typeName is enriched from the
 * mock types list if the backend left it blank.
 */
function toAdjustmentLine(a: AdjustmentEntryDto, category: 'loading' | 'discount'): AdjustmentLine {
  return {
    typeId:   a.typeId,
    typeName: a.typeName || resolveTypeName(a.typeId, category),
    format:   a.format,
    value:    a.value,
  };
}

/** Convert a QuoteRiskDto to the PDF preview's RiskItemData. */
function toRiskItemData(r: QuoteRiskDto): RiskItemData {
  return {
    description: r.description,
    sumInsured:  r.sumInsured,
    rate:        r.rate,
    loadings:    r.loadings.map(l => toAdjustmentLine(l, 'loading')),
    discounts:   r.discounts.map(d => toAdjustmentLine(d, 'discount')),
  };
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function QuoteDetailPage() {
  const navigate = useNavigate();
  const { id }   = useParams<{ id: string }>();
  const [pdfOpen, setPdfOpen] = useState(false);

  const quoteQuery = useQuery<QuoteDto>({
    queryKey: ['quotes', id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: QuoteDto }>(`/api/v1/quotes/${id}`);
      return res.data.data;
    },
    enabled: !!id,
  });

  // allow-mock: fallback while useQuery is in flight or for unknown ids
  const q = quoteQuery.data ?? MOCK_QUOTES.find(x => x.id === id) ?? MOCK_QUOTES[0];

  const canSubmit  = q.status === 'DRAFT';
  const canConvert = q.status === 'APPROVED';
  const canEdit    = q.status !== 'CONVERTED' && q.status !== 'APPROVED';
  const canDownloadPdf = q.status === 'APPROVED' || q.status === 'CONVERTED';

  // Project the API-shape quote into the PDF preview's stable internal shape.
  const pdfRisks: RiskItemData[] = q.risks.map(toRiskItemData);
  const pdfQuoteLoadings: AdjustmentLine[]  = q.quoteLoadings.map(l => toAdjustmentLine(l, 'loading'));
  const pdfQuoteDiscounts: AdjustmentLine[] = q.quoteDiscounts.map(d => toAdjustmentLine(d, 'discount'));

  const pdfData: QuotePdfData = {
    quoteNumber:       q.quoteNumber,
    issueDate:         q.createdAt.slice(0, 10),
    customerName:      q.customerName,
    productName:       q.productName,
    classOfBusiness:   q.classOfBusinessName,
    startDate:         q.policyStartDate,
    endDate:           q.policyEndDate,
    risks:             pdfRisks,
    quoteLoadings:     pdfQuoteLoadings,
    quoteDiscounts:    pdfQuoteDiscounts,
    selectedClauseIds: q.selectedClauseIds,
    inputterName:      q.inputterName ?? '',
    approverName:      q.approverName ?? '',
    validityDays:      MOCK_QUOTE_CONFIG.validityDays,
  };

  // Single source of truth for display totals
  const summary = computeQuoteSummary(pdfData);
  const itemResults = summary.items;
  const { totalQuoteLoading, totalQuoteDiscount, finalNet } = summary;

  const selectedClauses = INITIAL_CLAUSES.filter(c => q.selectedClauseIds.includes(c.id));

  if (quoteQuery.isLoading && !quoteQuery.data) {
    return (
      <div className="p-6 space-y-4 max-w-4xl">
        <Skeleton className="h-9 w-72" />
        <Skeleton className="h-32 w-full rounded-lg" />
        <Skeleton className="h-64 w-full rounded-lg" />
      </div>
    );
  }

  return (
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader
        title={q.quoteNumber}
        description={`${q.productName} · ${q.customerName}`}
        breadcrumb={
          <button onClick={() => navigate('/quotation')} className="text-sm text-muted-foreground hover:text-foreground">
            ← Quotation
          </button>
        }
        actions={
          <div className="flex items-center gap-2">
            <Badge variant={statusVariant[q.status]}>{q.status.toLowerCase()}</Badge>
            {canEdit       && <Button variant="outline" size="sm">Edit Quote</Button>}
            {canSubmit     && <Button size="sm">Submit for Approval</Button>}
            {canConvert    && <Button size="sm">Convert to Policy</Button>}
            {canDownloadPdf && (
              <Button variant="outline" size="sm" onClick={() => setPdfOpen(true)}>
                Download PDF
              </Button>
            )}
          </div>
        }
      />

      <div className="grid gap-5 lg:grid-cols-2">
        {/* Quote details */}
        <Card>
          <CardHeader><CardTitle>Quote Details</CardTitle></CardHeader>
          <CardContent>
            <Row label="Customer"      value={q.customerName} />
            <Row label="Product"       value={q.productName} />
            <Row label="Class"         value={q.classOfBusinessName} />
            <Row label="Business Type" value={q.businessType} />
            <Row label="Period"        value={`${q.policyStartDate} → ${q.policyEndDate}`} />
            {q.inputterName && <Row label="Prepared by" value={q.inputterName} />}
            {q.approverName && <Row label="Approved by" value={q.approverName} />}
          </CardContent>
        </Card>

        {/* Premium summary */}
        <Card>
          <CardHeader><CardTitle>Premium Summary</CardTitle></CardHeader>
          <CardContent>
            {itemResults.map((item, i) => (
              <div key={i} className="py-2.5" style={{ boxShadow: i < itemResults.length - 1 ? '0 1px 0 var(--border)' : undefined }}>
                <p className="text-xs font-medium text-muted-foreground mb-1">Item {i + 1} — {item.description}</p>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Gross</span>
                  <span>{fmt(item.gross)}</span>
                </div>
                {item.totalLoading > 0 && (
                  <div className="flex justify-between text-sm text-amber-700">
                    <span>+ Loadings</span>
                    <span>{fmt(item.totalLoading)}</span>
                  </div>
                )}
                {item.totalDiscount > 0 && (
                  <div className="flex justify-between text-sm text-rose-700">
                    <span>− Discounts</span>
                    <span>{fmt(item.totalDiscount)}</span>
                  </div>
                )}
                <div className="flex justify-between text-sm font-semibold mt-1">
                  <span>Item Net</span>
                  <span className="text-primary">{fmt(item.net)}</span>
                </div>
              </div>
            ))}

            <Separator className="my-3" />

            {q.quoteLoadings.length > 0 && (
              <div className="flex justify-between text-sm text-amber-700 mb-1">
                <span>+ Quote Loading</span>
                <span>{fmt(totalQuoteLoading)}</span>
              </div>
            )}
            {q.quoteDiscounts.length > 0 && (
              <div className="flex justify-between text-sm text-rose-700 mb-1">
                <span>− Quote Discount</span>
                <span>{fmt(totalQuoteDiscount)}</span>
              </div>
            )}

            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-foreground">Final Net Premium</span>
              <span className="text-lg font-semibold text-primary">{fmt(finalNet)}</span>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Risk items detail */}
      {q.risks.length > 0 && (
        <Card>
          <CardHeader><CardTitle>Risk Items</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            {itemResults.map((item, i) => (
              <div key={i} className={i < itemResults.length - 1 ? 'pb-4 border-b' : ''}>
                <p className="text-sm font-semibold mb-2">Item {i + 1} — {item.description}</p>
                <div className="grid grid-cols-3 gap-3 text-sm mb-2">
                  <div><p className="text-xs text-muted-foreground">Sum Insured</p><p className="font-medium">{fmt(item.sumInsured)}</p></div>
                  <div><p className="text-xs text-muted-foreground">Rate</p><p className="font-medium">{item.rate}%</p></div>
                  <div><p className="text-xs text-muted-foreground">Gross Premium</p><p className="font-medium">{fmt(item.gross)}</p></div>
                </div>
                {item.loadings.length > 0 && (
                  <div className="mb-2">
                    <p className="text-xs font-medium text-amber-700 mb-1">Loadings</p>
                    {item.loadings.map((l, li) => {
                      const amt = l.format === 'PERCENT' ? item.gross * l.value / 100 : l.value;
                      return (
                        <div key={li} className="flex justify-between text-xs text-muted-foreground">
                          <span>{l.typeName} ({l.format === 'PERCENT' ? `${l.value}%` : 'Flat'})</span>
                          <span className="text-amber-700">+{fmt(amt)}</span>
                        </div>
                      );
                    })}
                  </div>
                )}
                {item.discounts.length > 0 && (
                  <div className="mb-2">
                    <p className="text-xs font-medium text-rose-700 mb-1">Discounts</p>
                    {item.discounts.map((d, di) => {
                      const base = item.gross + item.totalLoading;
                      const amt  = d.format === 'PERCENT' ? base * d.value / 100 : d.value;
                      return (
                        <div key={di} className="flex justify-between text-xs text-muted-foreground">
                          <span>{d.typeName} ({d.format === 'PERCENT' ? `${d.value}%` : 'Flat'})</span>
                          <span className="text-rose-700">−{fmt(amt)}</span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {/* Clauses */}
      {selectedClauses.length > 0 && (
        <Card>
          <CardHeader><CardTitle>Applicable Clauses</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            {selectedClauses.map((c, i) => (
              <div key={c.id} className={i < selectedClauses.length - 1 ? 'pb-3 border-b' : ''}>
                <p className="text-sm font-semibold">{c.title}</p>
                <p className="text-xs text-muted-foreground mt-0.5 leading-relaxed">{c.text}</p>
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {/* PDF Preview Dialog */}
      <QuotePdfPreview
        open={pdfOpen}
        onOpenChange={setPdfOpen}
        data={canDownloadPdf ? pdfData : null}
      />
    </div>
  );
}
