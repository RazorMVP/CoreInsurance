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

// ── Self-contained HTML generator for the print popup ────────────────────────
function buildPrintHtml(data: QuotePdfData): string {
  const validityDays = MOCK_QUOTE_CONFIG.validityDays;
  const items        = data.risks.map(r => ({ ...r, ...computeItemAmounts(r) }));
  const totalGross   = items.reduce((s, r) => s + r.gross, 0);
  const totalNets    = items.reduce((s, r) => s + r.net, 0);
  const qLoading     = data.quoteLoadings.reduce((s, l) =>
    s + (l.format === 'PERCENT' ? totalGross * l.value / 100 : l.value), 0);
  const loadedBase   = totalNets + qLoading;
  const qDiscount    = data.quoteDiscounts.reduce((s, d) =>
    s + (d.format === 'PERCENT' ? loadedBase * d.value / 100 : d.value), 0);
  const finalNet     = Math.max(0, loadedBase - qDiscount);

  const expiryDate = (() => {
    const d = new Date(data.issueDate);
    d.setDate(d.getDate() + validityDays);
    return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'long', year: 'numeric' });
  })();

  const selectedClauses = INITIAL_CLAUSES.filter(c => data.selectedClauseIds.includes(c.id));

  const m = (n: number) =>
    `&#8358;${n.toLocaleString('en-NG', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

  const adjFmt = (l: AdjustmentLine) => l.format === 'PERCENT' ? `${l.value}%` : 'Flat';

  let risksHtml = '';
  items.forEach((item, i) => {
    risksHtml += `<p style="font-weight:bold;font-size:9pt;margin:10px 0 4px;">Item ${i + 1} — ${item.description}</p>
    <table><thead><tr>
      <th>Description</th><th style="text-align:right">Sum Insured</th>
      <th style="text-align:right">Rate (%)</th><th style="text-align:right">Gross Premium</th>
    </tr></thead><tbody><tr>
      <td>${item.description}</td><td style="text-align:right">${m(item.sumInsured)}</td>
      <td style="text-align:right">${item.rate}%</td><td style="text-align:right">${m(item.gross)}</td>
    </tr></tbody></table>`;
    if (item.loadings.length > 0) {
      risksHtml += `<table><thead>
        <tr><th colspan="3" style="color:#b45309">Loadings</th></tr>
        <tr><th>Type</th><th>Format</th><th style="text-align:right">Amount</th></tr>
      </thead><tbody>`;
      item.loadings.forEach(l => {
        const amt = l.format === 'PERCENT' ? item.gross * l.value / 100 : l.value;
        risksHtml += `<tr><td>${resolveTypeName(l.typeId, 'loading')}</td><td>${adjFmt(l)}</td>
          <td style="text-align:right;color:#b45309">${m(amt)}</td></tr>`;
      });
      risksHtml += '</tbody></table>';
    }
    if (item.discounts.length > 0) {
      risksHtml += `<table><thead>
        <tr><th colspan="3" style="color:#be185d">Discounts</th></tr>
        <tr><th>Type</th><th>Format</th><th style="text-align:right">Amount</th></tr>
      </thead><tbody>`;
      item.discounts.forEach(d => {
        const base = item.gross + item.totalLoading;
        const amt  = d.format === 'PERCENT' ? base * d.value / 100 : d.value;
        risksHtml += `<tr><td>${resolveTypeName(d.typeId, 'discount')}</td><td>${adjFmt(d)}</td>
          <td style="text-align:right;color:#be185d">${m(amt)}</td></tr>`;
      });
      risksHtml += '</tbody></table>';
    }
    risksHtml += `<p style="text-align:right;font-size:9pt;margin:2px 0 8px;">
      <strong>Item Net: </strong><span style="color:#0f766e;font-weight:bold;">${m(item.net)}</span></p>`;
  });

  let adjHtml = '';
  if (data.quoteLoadings.length > 0 || data.quoteDiscounts.length > 0) {
    adjHtml = `<h2>Quote-Level Adjustments</h2><table><tbody>
      <tr><td>Sum of Item Gross Premiums (% base)</td><td style="text-align:right">${m(totalGross)}</td></tr>
      <tr><td>Sum of Item Net Premiums</td><td style="text-align:right">${m(totalNets)}</td></tr>`;
    data.quoteLoadings.forEach(l => {
      const amt = l.format === 'PERCENT' ? totalGross * l.value / 100 : l.value;
      adjHtml += `<tr><td style="color:#b45309">+ ${resolveTypeName(l.typeId, 'loading')} (${adjFmt(l)})</td>
        <td style="text-align:right;color:#b45309">${m(amt)}</td></tr>`;
    });
    data.quoteDiscounts.forEach(d => {
      const amt = d.format === 'PERCENT' ? loadedBase * d.value / 100 : d.value;
      adjHtml += `<tr><td style="color:#be185d">- ${resolveTypeName(d.typeId, 'discount')} (${adjFmt(d)})</td>
        <td style="text-align:right;color:#be185d">${m(amt)}</td></tr>`;
    });
    adjHtml += '</tbody></table>';
  }

  const clausesHtml = selectedClauses.length === 0 ? '' : `<h2>Applicable Clauses</h2>
    ${selectedClauses.map((c, i) =>
      `<p style="font-weight:bold;font-size:9pt;margin-bottom:2px;">${i + 1}. ${c.title}</p>
       <p style="font-size:8.5pt;color:#444;margin-bottom:8px;">${c.text}</p>`
    ).join('')}`;

  return `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"/>
  <title>${data.quoteNumber} — Insurance Quotation</title>
  <style>
    *{box-sizing:border-box;margin:0;padding:0}
    body{font-family:Helvetica,Arial,sans-serif;font-size:10pt;color:#1a1a1a;padding:32px}
    h1{font-size:18pt;margin-bottom:4px}
    h2{font-size:10pt;text-transform:uppercase;letter-spacing:1px;color:#444;
       border-bottom:1px solid #ccc;padding-bottom:4px;margin:16px 0 8px}
    table{width:100%;border-collapse:collapse;margin-bottom:8px;font-size:9pt}
    th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;vertical-align:top}
    th{background:#f0f0f0;font-weight:bold}
    hr{border:none;border-top:1px solid #ccc;margin:10px 0}
    .hdr{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:16px}
    .hdr-right{text-align:right}
    .info{display:grid;grid-template-columns:1fr 1fr;gap:6px 24px;margin-bottom:12px}
    .lbl{font-size:8pt;color:#666;text-transform:uppercase;letter-spacing:.5px;margin-bottom:1px}
    .val{font-weight:bold}
    .net{border:2px solid #0f766e;background:#f0fdf9;padding:10px 14px;
         display:flex;justify-content:space-between;align-items:center;margin:12px 0}
    .sig{display:flex;gap:60px;margin-top:28px}
    .sig-b{flex:1;border-top:1px solid #888;padding-top:4px}
    .foot{font-size:8pt;color:#888;text-align:center;margin-top:24px}
    ol{margin:0 0 0 18px}li{font-size:9pt;margin-bottom:6px;line-height:1.4}
    @media print{body{padding:16px}@page{margin:16mm}}
  </style>
  </head><body>
  <div class="hdr">
    <div><h1>INSURANCE QUOTATION</h1>
      <p style="color:#666;font-size:9pt">NubSure &mdash; Powered by Nubeero Technologies</p></div>
    <div class="hdr-right">
      <p style="font-family:Courier,monospace;font-size:13pt;font-weight:bold">${data.quoteNumber}</p>
      <p style="color:#666;font-size:9pt">Issue Date: ${data.issueDate}</p>
    </div>
  </div><hr/>
  <div class="info">
    <div><p class="lbl">Prepared For</p><p class="val">${data.customerName}</p></div>
    <div><p class="lbl">Policy Period</p><p class="val">${data.startDate} to ${data.endDate}</p></div>
    <div><p class="lbl">Product</p><p class="val">${data.productName}</p></div>
    <div><p class="lbl">Quote Validity</p><p class="val">Valid ${validityDays} days (expires ${expiryDate})</p></div>
    <div><p class="lbl">Class of Business</p><p class="val">${data.classOfBusiness}</p></div>
  </div><hr/>
  <h2>Risk Details &amp; Premium Breakdown</h2>
  ${risksHtml}${adjHtml}
  <div class="net">
    <span style="font-size:11pt;font-weight:bold;color:#0f766e">FINAL NET PREMIUM</span>
    <span style="font-size:15pt;font-weight:bold;color:#0f766e">${m(finalNet)}</span>
  </div>
  ${clausesHtml}${clausesHtml ? '<hr/>' : ''}
  <h2>General Subjectivity</h2>
  <ol>
    <li>This quote is subject to <strong>no known loss or reported loss</strong> till date.</li>
    <li>This quotation is valid for <strong>${validityDays} days</strong> from the date of issue (${data.issueDate}).
        It will expire on <strong>${expiryDate}</strong>.</li>
    <li>This quote is subject to a <strong>satisfactory survey report</strong>.</li>
  </ol><hr/>
  <div class="sig">
    <div class="sig-b"><p style="font-weight:bold;font-size:9pt">${data.inputterName || '&mdash;'}</p>
      <p style="font-size:8pt;color:#666">Prepared by (Underwriter)</p></div>
    <div class="sig-b"><p style="font-weight:bold;font-size:9pt">${data.approverName || '&mdash;'}</p>
      <p style="font-size:8pt;color:#666">Approved by</p></div>
  </div>
  <p class="foot">NubSure by Nubeero Technologies &middot; Computer generated quotation.</p>
  <script>window.onload=function(){window.focus();window.print();}</script>
  </body></html>`;
}

// ── Dialog wrapper ────────────────────────────────────────────────────────────
interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  data:         QuotePdfData | null;
}

export default function QuotePdfPreview({ open, onOpenChange, data }: Props) {
  function handlePrint() {
    if (!data) return;
    const html    = buildPrintHtml(data);
    const blob    = new Blob([html], { type: 'text/html' });
    const blobUrl = URL.createObjectURL(blob);
    const win     = window.open(blobUrl, '_blank', 'width=900,height=700,scrollbars=yes');
    if (!win) {
      URL.revokeObjectURL(blobUrl);
      // eslint-disable-next-line no-alert
      window.alert('Pop-up blocked. Please allow pop-ups for this site and try again.');
      return;
    }
    setTimeout(() => URL.revokeObjectURL(blobUrl), 60_000);
  }

  if (!data) return null;

  return (
    <>
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Quote Preview — {data.quoteNumber}</DialogTitle>
            <DialogDescription>
              Review the quote below. Click "Print / Save as PDF" to open a print-ready popup — choose "Save as PDF" in your browser print dialog.
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
