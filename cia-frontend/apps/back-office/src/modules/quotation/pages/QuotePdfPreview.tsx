import {
  Button,
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
  Separator,
} from '@cia/ui';
import { INITIAL_CLAUSES } from './clauses-shared';
import { MOCK_DISCOUNT_TYPES, MOCK_LOADING_TYPES, MOCK_QUOTE_CONFIG } from '../../setup/pages/policy-specs/quote-config-types';

// ── Types for the PDF data model ──────────────────────────────────────────────
export interface AdjustmentLine {
  typeId:  string;
  format:  'PERCENT' | 'FLAT';
  value:   number;
}

export interface RiskItemData {
  description:  string;
  sumInsured:   number;
  rate:         number;
  loadings:     AdjustmentLine[];
  discounts:    AdjustmentLine[];
}

export interface QuotePdfData {
  quoteNumber:       string;
  issueDate:         string;
  customerName:      string;
  productName:       string;
  classOfBusiness:   string;
  startDate:         string;
  endDate:           string;
  risks:             RiskItemData[];
  quoteLoadings:     AdjustmentLine[];
  quoteDiscounts:    AdjustmentLine[];
  selectedClauseIds: string[];
  inputterName:      string;
  approverName:      string;
}

// ── Helpers ───────────────────────────────────────────────────────────────────
function fmt(n: number) {
  return `₦${n.toLocaleString('en-NG', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function resolveTypeName(typeId: string, category: 'loading' | 'discount') {
  const list = category === 'loading' ? MOCK_LOADING_TYPES : MOCK_DISCOUNT_TYPES;
  return list.find(t => t.id === typeId)?.name ?? typeId;
}

function computeItemAmounts(item: RiskItemData) {
  const gross = (item.sumInsured * item.rate) / 100;
  const totalLoading = item.loadings.reduce((s, l) => s + (l.format === 'PERCENT' ? gross * l.value / 100 : l.value), 0);
  const loaded = gross + totalLoading;
  const totalDiscount = item.discounts.reduce((s, d) => s + (d.format === 'PERCENT' ? loaded * d.value / 100 : d.value), 0);
  return { gross, totalLoading, loaded, totalDiscount, net: Math.max(0, loaded - totalDiscount) };
}

// ── Print content component ───────────────────────────────────────────────────
export function PrintContent({ data }: { data: QuotePdfData }) {
  const validityDays = MOCK_QUOTE_CONFIG.validityDays;

  const itemResults = data.risks.map(r => ({ ...r, ...computeItemAmounts(r) }));
  const totalGross    = itemResults.reduce((s, r) => s + r.gross, 0);
  const totalItemNets = itemResults.reduce((s, r) => s + r.net, 0);

  const totalQuoteLoading = data.quoteLoadings.reduce((s, l) => {
    return s + (l.format === 'PERCENT' ? totalGross * l.value / 100 : l.value);
  }, 0);
  const quoteLoadedBase = totalItemNets + totalQuoteLoading;
  const totalQuoteDiscount = data.quoteDiscounts.reduce((s, d) => {
    return s + (d.format === 'PERCENT' ? quoteLoadedBase * d.value / 100 : d.value);
  }, 0);
  const finalNet = Math.max(0, quoteLoadedBase - totalQuoteDiscount);

  const selectedClauses = INITIAL_CLAUSES.filter(c => data.selectedClauseIds.includes(c.id));

  const expiryDate = (() => {
    const d = new Date(data.issueDate);
    d.setDate(d.getDate() + validityDays);
    return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'long', year: 'numeric' });
  })();

  const cell = 'border border-gray-300 px-2 py-1 text-xs';
  const th   = `${cell} bg-gray-100 font-semibold text-left`;

  return (
    <div className="font-sans text-gray-900 p-8 max-w-3xl mx-auto" id="quote-pdf-content">
      {/* Header */}
      <div className="flex justify-between items-start mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">INSURANCE QUOTATION</h1>
          <p className="text-sm text-gray-500 mt-1">NubSure — Powered by Nubeero Technologies</p>
        </div>
        <div className="text-right">
          <p className="text-lg font-mono font-bold text-gray-800">{data.quoteNumber}</p>
          <p className="text-xs text-gray-500">Issue Date: {data.issueDate}</p>
        </div>
      </div>

      <Separator className="mb-5" />

      {/* Customer + Product info */}
      <div className="grid grid-cols-2 gap-6 mb-6">
        <div className="space-y-3">
          <div>
            <p className="text-xs text-gray-500 font-medium uppercase tracking-wide">Prepared For</p>
            <p className="text-sm font-semibold text-gray-900">{data.customerName}</p>
          </div>
          <div>
            <p className="text-xs text-gray-500 font-medium uppercase tracking-wide">Product</p>
            <p className="text-sm font-semibold text-gray-900">{data.productName}</p>
          </div>
          <div>
            <p className="text-xs text-gray-500 font-medium uppercase tracking-wide">Class of Business</p>
            <p className="text-sm font-semibold text-gray-900">{data.classOfBusiness}</p>
          </div>
        </div>
        <div className="space-y-3">
          <div>
            <p className="text-xs text-gray-500 font-medium uppercase tracking-wide">Policy Period</p>
            <p className="text-sm font-semibold text-gray-900">{data.startDate} → {data.endDate}</p>
          </div>
          <div>
            <p className="text-xs text-gray-500 font-medium uppercase tracking-wide">Quote Validity</p>
            <p className="text-sm font-semibold text-gray-900">Valid for {validityDays} days from issue</p>
          </div>
        </div>
      </div>

      <Separator className="mb-5" />

      {/* Risk items */}
      <h2 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">Risk Details & Premium Breakdown</h2>
      {itemResults.map((item, i) => (
        <div key={i} className="mb-5">
          <p className="text-xs font-semibold text-gray-600 mb-2">Item {i + 1} — {item.description}</p>
          <table className="w-full border-collapse text-xs mb-1">
            <thead>
              <tr>
                <th className={th}>Description</th>
                <th className={`${th} text-right`}>Sum Insured</th>
                <th className={`${th} text-right`}>Rate (%)</th>
                <th className={`${th} text-right`}>Gross Premium</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className={cell}>{item.description}</td>
                <td className={`${cell} text-right tabular-nums`}>{fmt(item.sumInsured)}</td>
                <td className={`${cell} text-right tabular-nums`}>{item.rate}%</td>
                <td className={`${cell} text-right tabular-nums`}>{fmt(item.gross)}</td>
              </tr>
            </tbody>
          </table>

          {item.loadings.length > 0 && (
            <table className="w-full border-collapse text-xs mb-1">
              <thead>
                <tr>
                  <th className={th} colSpan={3}>Loadings</th>
                </tr>
                <tr>
                  <th className={th}>Type</th>
                  <th className={th}>Format</th>
                  <th className={`${th} text-right`}>Amount</th>
                </tr>
              </thead>
              <tbody>
                {item.loadings.map((l, li) => {
                  const amt = l.format === 'PERCENT' ? item.gross * l.value / 100 : l.value;
                  return (
                    <tr key={li}>
                      <td className={cell}>{resolveTypeName(l.typeId, 'loading')}</td>
                      <td className={cell}>{l.format === 'PERCENT' ? `${l.value}%` : 'Flat'}</td>
                      <td className={`${cell} text-right tabular-nums`}>{fmt(amt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}

          {item.discounts.length > 0 && (
            <table className="w-full border-collapse text-xs mb-1">
              <thead>
                <tr>
                  <th className={th} colSpan={3}>Discounts</th>
                </tr>
                <tr>
                  <th className={th}>Type</th>
                  <th className={th}>Format</th>
                  <th className={`${th} text-right`}>Amount</th>
                </tr>
              </thead>
              <tbody>
                {item.discounts.map((d, di) => {
                  const base = item.gross + item.totalLoading;
                  const amt  = d.format === 'PERCENT' ? base * d.value / 100 : d.value;
                  return (
                    <tr key={di}>
                      <td className={cell}>{resolveTypeName(d.typeId, 'discount')}</td>
                      <td className={cell}>{d.format === 'PERCENT' ? `${d.value}%` : 'Flat'}</td>
                      <td className={`${cell} text-right tabular-nums`}>{fmt(amt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}

          <div className="flex justify-end mt-1">
            <span className="text-xs font-semibold text-gray-700 mr-3">Item Net Premium:</span>
            <span className="text-xs font-bold text-teal-700 tabular-nums">{fmt(item.net)}</span>
          </div>
        </div>
      ))}

      {/* Quote-level adjustments */}
      {(data.quoteLoadings.length > 0 || data.quoteDiscounts.length > 0) && (
        <>
          <Separator className="my-4" />
          <h2 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">Quote-Level Adjustments</h2>
          <table className="w-full border-collapse text-xs mb-3">
            <tbody>
              <tr>
                <td className={cell}>Sum of Item Gross Premiums (base for % adjustments)</td>
                <td className={`${cell} text-right tabular-nums font-semibold`}>{fmt(totalGross)}</td>
              </tr>
              <tr>
                <td className={cell}>Sum of Item Net Premiums</td>
                <td className={`${cell} text-right tabular-nums font-semibold`}>{fmt(totalItemNets)}</td>
              </tr>
              {data.quoteLoadings.map((l, i) => {
                const amt = l.format === 'PERCENT' ? totalGross * l.value / 100 : l.value;
                return (
                  <tr key={i}>
                    <td className={cell}>+ Loading: {resolveTypeName(l.typeId, 'loading')} ({l.format === 'PERCENT' ? `${l.value}%` : 'Flat'})</td>
                    <td className={`${cell} text-right tabular-nums`}>{fmt(amt)}</td>
                  </tr>
                );
              })}
              {data.quoteDiscounts.map((d, i) => {
                const amt = d.format === 'PERCENT' ? quoteLoadedBase * d.value / 100 : d.value;
                return (
                  <tr key={i}>
                    <td className={cell}>- Discount: {resolveTypeName(d.typeId, 'discount')} ({d.format === 'PERCENT' ? `${d.value}%` : 'Flat'})</td>
                    <td className={`${cell} text-right tabular-nums`}>{fmt(amt)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </>
      )}

      {/* Final net */}
      <div className="rounded-lg border-2 border-teal-600 bg-teal-50 px-4 py-3 flex justify-between items-center mb-6">
        <span className="text-sm font-bold text-teal-800">FINAL NET PREMIUM</span>
        <span className="text-xl font-bold text-teal-700 tabular-nums">{fmt(finalNet)}</span>
      </div>

      {/* Clauses */}
      {selectedClauses.length > 0 && (
        <>
          <Separator className="mb-4" />
          <h2 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">Applicable Clauses</h2>
          <div className="space-y-3 mb-6">
            {selectedClauses.map((c, i) => (
              <div key={c.id}>
                <p className="text-xs font-semibold text-gray-800">{i + 1}. {c.title}</p>
                <p className="text-xs text-gray-600 mt-0.5 leading-relaxed">{c.text}</p>
              </div>
            ))}
          </div>
        </>
      )}

      <Separator className="mb-4" />

      {/* General Subjectivity */}
      <h2 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">General Subjectivity</h2>
      <ol className="list-decimal list-inside space-y-2 mb-8">
        <li className="text-xs text-gray-700 leading-relaxed">
          This quote is subject to <strong>no known loss or reported loss</strong> till date.
        </li>
        <li className="text-xs text-gray-700 leading-relaxed">
          This quotation is valid for <strong>{validityDays} days</strong> from the date of issue ({data.issueDate}).
          It will expire on <strong>{expiryDate}</strong>.
        </li>
        <li className="text-xs text-gray-700 leading-relaxed">
          This quote is subject to a <strong>satisfactory survey report</strong>.
        </li>
      </ol>

      {/* Signatures */}
      <div className="grid grid-cols-2 gap-10 mt-6">
        <div className="space-y-2">
          <div className="border-b border-gray-400 pb-1" />
          <p className="text-xs font-semibold text-gray-700">{data.inputterName}</p>
          <p className="text-xs text-gray-500">Prepared by (Underwriter)</p>
        </div>
        <div className="space-y-2">
          <div className="border-b border-gray-400 pb-1" />
          <p className="text-xs font-semibold text-gray-700">{data.approverName}</p>
          <p className="text-xs text-gray-500">Approved by</p>
        </div>
      </div>

      <p className="text-[10px] text-gray-400 text-center mt-8">
        NubSure by Nubeero Technologies · This quotation is computer generated.
      </p>
    </div>
  );
}

// ── Dialog wrapper ────────────────────────────────────────────────────────────
interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  data:         QuotePdfData | null;
}

export default function QuotePdfPreview({ open, onOpenChange, data }: Props) {
  function handlePrint() {
    const styleEl = document.createElement('style');
    styleEl.id = '__quote-print-style';
    styleEl.textContent = [
      '@media print {',
      '  body > * { display: none !important; }',
      '  #quote-print-portal { display: block !important; position: fixed; inset: 0; background: white; z-index: 99999; overflow: auto; }',
      '}',
    ].join('\n');
    document.head.appendChild(styleEl);

    const portal = document.getElementById('quote-print-portal');
    if (portal) portal.style.display = 'none';

    window.print();

    setTimeout(() => {
      document.getElementById('__quote-print-style')?.remove();
    }, 500);
  }

  if (!data) return null;

  return (
    <>
      <div id="quote-print-portal" style={{ display: 'none' }} aria-hidden="true">
        <PrintContent data={data} />
      </div>

      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Quote Preview — {data.quoteNumber}</DialogTitle>
            <DialogDescription>
              Review before printing. Use your browser's "Save as PDF" option when the print dialog opens.
            </DialogDescription>
          </DialogHeader>

          <div className="border rounded-lg overflow-hidden bg-white">
            <PrintContent data={data} />
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button>
            <Button onClick={handlePrint}>Print / Save as PDF</Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
