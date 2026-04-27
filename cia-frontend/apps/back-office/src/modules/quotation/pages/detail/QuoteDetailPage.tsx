import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader, Separator,
} from '@cia/ui';
import type { QuoteDto } from '@cia/api-client';
import QuotePdfPreview, { type QuotePdfData, type AdjustmentLine, type RiskItemData } from '../QuotePdfPreview';
import { INITIAL_CLAUSES } from '../clauses-shared';
import { MOCK_DISCOUNT_TYPES, MOCK_LOADING_TYPES } from '../../../setup/pages/policy-specs/quote-config-types';

// ── Mock quote shape ──────────────────────────────────────────────────────────
interface MockQuote {
  id: string; quoteNumber: string; version: number;
  customerName: string; customerId: string;
  productId: string; productName: string; classOfBusinessName: string;
  businessType: string; status: QuoteDto['status'];
  startDate: string; endDate: string; issueDate: string;
  createdAt: string; updatedAt: string;
  inputterName: string; approverName: string;
  risks: RiskItemData[];
  quoteLoadings: AdjustmentLine[];
  quoteDiscounts: AdjustmentLine[];
  selectedClauseIds: string[];
}

// ── Extended mock data (all quotes, looked up by id from useParams) ────────────
const MOCK_QUOTES: MockQuote[] = [
  {
    id: 'q1', quoteNumber: 'QUO-2026-00001', version: 1,
    customerName: 'Chioma Okafor', customerId: 'c1',
    productId: 'p1', productName: 'Private Motor Comprehensive', classOfBusinessName: 'Motor (Private)',
    businessType: 'DIRECT', status: 'APPROVED' as QuoteDto['status'],
    startDate: '2026-02-01', endDate: '2027-02-01',
    createdAt: '2026-01-28', updatedAt: '2026-01-30', issueDate: '2026-01-28',
    inputterName: 'Chidi Okafor', approverName: 'Adeola Bello',
    risks: [
      {
        description: '2022 Toyota Camry, Reg: LND-001-AA',
        sumInsured: 3_500_000, rate: 2.25,
        loadings:  [{ typeId: 'l1', format: 'PERCENT' as const, value: 5 }],
        discounts: [{ typeId: 'd1', format: 'PERCENT' as const, value: 2.5 }],
      },
    ],
    quoteLoadings: [], quoteDiscounts: [],
    selectedClauseIds: ['c1', 'c2'],
  },
  {
    id: 'q2', quoteNumber: 'QUO-2026-00002', version: 2,
    customerName: 'Alaba Trading Co.', customerId: 'c2',
    productId: 'p3', productName: 'Fire & Burglary Standard', classOfBusinessName: 'Fire & Burglary',
    businessType: 'DIRECT', status: 'SUBMITTED' as QuoteDto['status'],
    startDate: '2026-03-01', endDate: '2027-03-01',
    createdAt: '2026-02-01', updatedAt: '2026-02-05', issueDate: '2026-02-01',
    inputterName: 'Chidi Okafor', approverName: '',
    risks: [
      {
        description: 'Mixed stock — Eko Hotel Annexe, Warehouse B',
        sumInsured: 10_000_000, rate: 0.80,
        loadings:  [{ typeId: 'l2', format: 'PERCENT' as const, value: 10 }],
        discounts: [{ typeId: 'd3', format: 'FLAT' as const, value: 5_000 }],
      },
      {
        description: 'Fixtures & fittings — Warehouse B',
        sumInsured: 5_000_000, rate: 0.80,
        loadings:  [],
        discounts: [{ typeId: 'd2', format: 'PERCENT' as const, value: 5 }],
      },
    ],
    quoteLoadings: [{ typeId: 'l1', format: 'PERCENT' as const, value: 2.5 }],
    quoteDiscounts: [{ typeId: 'd4', format: 'FLAT' as const, value: 10_000 }],
    selectedClauseIds: ['c5', 'c6', 'c8'],
  },
  {
    id: 'q3', quoteNumber: 'QUO-2026-00003', version: 1,
    customerName: 'Emeka Eze', customerId: 'c3',
    productId: 'p1', productName: 'Private Motor Comprehensive', classOfBusinessName: 'Motor (Private)',
    businessType: 'DIRECT', status: 'DRAFT' as QuoteDto['status'],
    startDate: '2026-03-15', endDate: '2027-03-15',
    createdAt: '2026-02-10', updatedAt: '2026-02-10', issueDate: '2026-02-10',
    inputterName: 'Chidi Okafor', approverName: '',
    risks: [
      { description: '2020 Honda Accord, Reg: ABJ-222-XY', sumInsured: 2_200_000, rate: 2.25, loadings: [], discounts: [] },
    ],
    quoteLoadings: [], quoteDiscounts: [], selectedClauseIds: [],
  },
  {
    id: 'q4', quoteNumber: 'QUO-2026-00004', version: 1,
    customerName: 'Chioma Okafor', customerId: 'c1',
    productId: 'p4', productName: 'Marine Cargo Open Cover', classOfBusinessName: 'Marine Cargo',
    businessType: 'DIRECT', status: 'CONVERTED' as QuoteDto['status'],
    startDate: '2026-01-15', endDate: '2027-01-15',
    createdAt: '2026-01-10', updatedAt: '2026-01-15', issueDate: '2026-01-10',
    inputterName: 'Chidi Okafor', approverName: 'Adeola Bello',
    risks: [
      { description: 'General cargo — Lagos to Kano open cover', sumInsured: 8_000_000, rate: 0.75, loadings: [], discounts: [] },
    ],
    quoteLoadings: [], quoteDiscounts: [], selectedClauseIds: ['c7'],
  },
  {
    id: 'q5', quoteNumber: 'QUO-2026-00005', version: 3,
    customerName: 'Ngozi Adeyemi', customerId: 'c5',
    productId: 'p1', productName: 'Private Motor Comprehensive', classOfBusinessName: 'Motor (Private)',
    businessType: 'DIRECT', status: 'REJECTED' as QuoteDto['status'],
    startDate: '2026-02-20', endDate: '2027-02-20',
    createdAt: '2026-02-08', updatedAt: '2026-02-12', issueDate: '2026-02-08',
    inputterName: 'Chidi Okafor', approverName: '',
    risks: [
      { description: '2019 Mercedes GLE 450, Reg: LND-999-ZZ', sumInsured: 4_000_000, rate: 2.25, loadings: [], discounts: [] },
    ],
    quoteLoadings: [], quoteDiscounts: [], selectedClauseIds: [],
  },
];

// ── Version history mock ──────────────────────────────────────────────────────
const VERSION_HISTORY: Record<string, { version: number; date: string; change: string; user: string }[]> = {
  q1: [{ version: 1, date: '28 Jan 2026', change: 'Initial draft created.', user: 'Chidi Okafor' }],
  q2: [
    { version: 2, date: '05 Feb 2026', change: 'Sum insured increased from ₦12M to ₦15M. Rate unchanged.', user: 'Chidi Okafor' },
    { version: 1, date: '01 Feb 2026', change: 'Initial draft created.', user: 'Chidi Okafor' },
  ],
  q3: [{ version: 1, date: '10 Feb 2026', change: 'Initial draft created.', user: 'Chidi Okafor' }],
  q4: [{ version: 1, date: '10 Jan 2026', change: 'Initial draft created.', user: 'Chidi Okafor' }],
  q5: [
    { version: 3, date: '12 Feb 2026', change: 'Sum insured revised upward. Loading removed.', user: 'Chidi Okafor' },
    { version: 2, date: '10 Feb 2026', change: 'Rate adjusted to 2.25%.', user: 'Chidi Okafor' },
    { version: 1, date: '08 Feb 2026', change: 'Initial draft created.', user: 'Chidi Okafor' },
  ],
};

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

function computeItemNet(si: number, rate: number, loadings: { format: string; value: number }[], discounts: { format: string; value: number }[]) {
  const gross = (si * rate) / 100;
  const totalLoading = loadings.reduce((s, l) => s + (l.format === 'PERCENT' ? gross * l.value / 100 : l.value), 0);
  const loaded = gross + totalLoading;
  const totalDiscount = discounts.reduce((s, d) => s + (d.format === 'PERCENT' ? loaded * d.value / 100 : d.value), 0);
  return { gross, totalLoading, loaded, totalDiscount, net: Math.max(0, loaded - totalDiscount) };
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function QuoteDetailPage() {
  const navigate = useNavigate();
  const { id }   = useParams<{ id: string }>();
  const [pdfOpen, setPdfOpen] = useState(false);

  const q = MOCK_QUOTES.find(x => x.id === id) ?? MOCK_QUOTES[0];
  const versionHistory = VERSION_HISTORY[q.id] ?? [];

  const canSubmit  = q.status === 'DRAFT';
  const canConvert = q.status === 'APPROVED';
  const canEdit    = q.status !== 'CONVERTED' && q.status !== 'APPROVED';
  const canDownloadPdf = q.status === 'APPROVED' || q.status === 'CONVERTED';

  // Build QuotePdfData for the preview
  const pdfData: QuotePdfData = {
    quoteNumber:       q.quoteNumber,
    issueDate:         q.issueDate,
    customerName:      q.customerName,
    productName:       q.productName,
    classOfBusiness:   q.classOfBusinessName,
    startDate:         q.startDate,
    endDate:           q.endDate,
    risks:             q.risks,
    quoteLoadings:     q.quoteLoadings,
    quoteDiscounts:    q.quoteDiscounts,
    selectedClauseIds: q.selectedClauseIds,
    inputterName:      q.inputterName,
    approverName:      q.approverName,
  };

  // Compute totals for display
  const itemResults = q.risks.map(r => ({ ...r, ...computeItemNet(r.sumInsured, r.rate, r.loadings, r.discounts) }));
  const totalGross    = itemResults.reduce((s, r) => s + r.gross, 0);
  const totalItemNets = itemResults.reduce((s, r) => s + r.net, 0);
  const totalQuoteLoading = q.quoteLoadings.reduce((s, l) => s + (l.format === 'PERCENT' ? totalGross * l.value / 100 : l.value), 0);
  const quoteLoadedBase   = totalItemNets + totalQuoteLoading;
  const totalQuoteDiscount = q.quoteDiscounts.reduce((s, d) => s + (d.format === 'PERCENT' ? quoteLoadedBase * d.value / 100 : d.value), 0);
  const finalNet = Math.max(0, quoteLoadedBase - totalQuoteDiscount);

  const selectedClauses = INITIAL_CLAUSES.filter(c => q.selectedClauseIds.includes(c.id));

  return (
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader
        title={q.quoteNumber}
        description={`v${q.version} · ${q.productName} · ${q.customerName}`}
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
            <Row label="Period"        value={`${q.startDate} → ${q.endDate}`} />
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
                          <span>{resolveTypeName(l.typeId, 'loading')} ({l.format === 'PERCENT' ? `${l.value}%` : 'Flat'})</span>
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
                          <span>{resolveTypeName(d.typeId, 'discount')} ({d.format === 'PERCENT' ? `${d.value}%` : 'Flat'})</span>
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

      {/* Version history */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>Version History</CardTitle>
            <Badge variant="outline" className="text-xs">v{q.version} current</Badge>
          </div>
        </CardHeader>
        <CardContent className="space-y-0">
          {versionHistory.map((v, i) => (
            <div
              key={v.version}
              className="flex gap-4 py-4"
              style={i < versionHistory.length - 1 ? { boxShadow: '0 1px 0 var(--border)' } : undefined}
            >
              <div className="flex flex-col items-center gap-1">
                <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold ${v.version === q.version ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'}`}>
                  v{v.version}
                </div>
                {i < versionHistory.length - 1 && <div className="w-px flex-1 bg-border" />}
              </div>
              <div className="pb-2 space-y-1">
                <div className="flex items-center gap-2">
                  <p className="text-sm font-medium text-foreground">{v.change}</p>
                  {v.version === q.version && (
                    <Badge variant="active" className="text-[10px]">Current</Badge>
                  )}
                </div>
                <p className="text-xs text-muted-foreground">{v.date} · {v.user}</p>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* PDF Preview Dialog */}
      <QuotePdfPreview
        open={pdfOpen}
        onOpenChange={setPdfOpen}
        data={canDownloadPdf ? pdfData : null}
      />
    </div>
  );
}
