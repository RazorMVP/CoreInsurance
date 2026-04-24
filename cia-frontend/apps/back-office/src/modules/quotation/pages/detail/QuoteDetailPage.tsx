import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader, Separator,
} from '@cia/ui';
import type { QuoteDto } from '@cia/api-client';

type MockQuote = {
  id: string; quoteNumber: string; version: number;
  customerId: string; customerName: string;
  productId: string; productName: string; classOfBusinessName: string;
  businessType: string; status: QuoteDto['status'];
  sumInsured: number; premium: number; discount: number; netPremium: number;
  startDate: string; endDate: string; riskDescription: string;
  createdAt: string; updatedAt: string;
};

// Mock data — replace with useGet(`/api/v1/quotes/${id}`)
const mockQuote: MockQuote = {
  id: 'q2', quoteNumber: 'QUO-2026-00002', version: 2,
  customerName: 'Alaba Trading Co.', customerId: 'c2',
  productId: 'p3', productName: 'Fire & Burglary Standard', classOfBusinessName: 'Fire & Burglary',
  businessType: 'DIRECT', status: 'SUBMITTED',
  sumInsured: 15_000_000, premium: 120_000, discount: 5_000, netPremium: 115_000,
  startDate: '2026-03-01', endDate: '2027-03-01',
  createdAt: '2026-02-01', updatedAt: '2026-02-05',
  riskDescription: 'Mixed stock and fixtures in Eko Hotel Annexe, Warehouse B',
};

// Version history (newest first)
const versionHistory = [
  { version: 2, date: '05 Feb 2026', change: 'Sum insured increased from ₦12M to ₦15M. Rate unchanged.', user: 'Chidi Okafor' },
  { version: 1, date: '01 Feb 2026', change: 'Initial draft created.', user: 'Chidi Okafor' },
];

const statusVariant: Record<string, 'active'|'pending'|'rejected'|'draft'|'cancelled'> = {
  APPROVED: 'active', SUBMITTED: 'pending', DRAFT: 'draft', CONVERTED: 'active', REJECTED: 'rejected', EXPIRED: 'cancelled',
};

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2.5" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-40 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

export default function QuoteDetailPage() {
  const navigate = useNavigate();
  const q = mockQuote;

  const canSubmit  = q.status === 'DRAFT';
  const canConvert = q.status === 'APPROVED';
  const canEdit    = q.status !== 'CONVERTED' && q.status !== 'APPROVED';

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
            {canEdit    && <Button variant="outline" size="sm">Edit Quote</Button>}
            {canSubmit  && <Button size="sm">Submit for Approval</Button>}
            {canConvert && <Button size="sm">Convert to Policy</Button>}
          </div>
        }
      />

      <div className="grid gap-5 lg:grid-cols-2">
        {/* Quote details */}
        <Card>
          <CardHeader><CardTitle>Quote Details</CardTitle></CardHeader>
          <CardContent>
            <Row label="Customer"     value={q.customerName} />
            <Row label="Product"      value={q.productName} />
            <Row label="Class"        value={q.classOfBusinessName} />
            <Row label="Business Type" value={q.businessType} />
            <Row label="Period"       value={`${q.startDate} → ${q.endDate}`} />
            <Row label="Risk"         value={q.riskDescription} />
          </CardContent>
        </Card>

        {/* Premium summary */}
        <Card>
          <CardHeader><CardTitle>Premium Summary</CardTitle></CardHeader>
          <CardContent>
            <Row label="Sum Insured"    value={`₦${q.sumInsured.toLocaleString()}`} />
            <Row label="Gross Premium"  value={`₦${q.premium.toLocaleString()}`} />
            {q.discount > 0 && <Row label="Discount" value={`−₦${q.discount.toLocaleString()}`} />}
            <Separator className="my-3" />
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-foreground">Net Premium</span>
              <span className="text-lg font-semibold text-primary">₦{q.netPremium.toLocaleString()}</span>
            </div>
          </CardContent>
        </Card>
      </div>

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
              {/* Timeline dot */}
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
    </div>
  );
}
