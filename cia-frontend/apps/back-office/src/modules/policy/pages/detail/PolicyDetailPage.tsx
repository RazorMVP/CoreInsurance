import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader,
  Separator, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import type { PolicyDto } from '@cia/api-client';

type MockPolicy = Omit<PolicyDto, 'updatedAt'> & {
  updatedAt: string;
  riskDescription: string;
  paymentTerms: string;
  commission: number;
  debitNoteNumber?: string;
  surveyRequired: boolean;
  surveyStatus?: 'PENDING' | 'IN_PROGRESS' | 'APPROVED' | 'OVERRIDDEN';
  clauses: { id: string; title: string; text: string }[];
};

const mockPolicy: MockPolicy = {
  id: 'pol1', policyNumber: 'POL-2026-00001', quoteId: 'q4',
  customerId: 'c1', customerName: 'Chioma Okafor',
  productId: 'p1', productName: 'Private Motor Comprehensive',
  classOfBusinessId: '1', classOfBusinessName: 'Motor (Private)',
  businessType: 'DIRECT', status: 'ACTIVE',
  sumInsured: 3_500_000, premium: 78_750, netPremium: 78_750,
  startDate: '2026-02-01', endDate: '2027-02-01',
  naicomUid: 'NMC-2026-00001', niidUid: 'NIID-2026-00001',
  documentPath: '/docs/pol1.pdf', debitNoteId: 'dn1',
  createdAt: '2026-01-30', updatedAt: '2026-02-01',
  riskDescription: '2022 Toyota Camry 2.5L, Reg: LND-001-AA, Chassis: ABC123',
  paymentTerms: 'Immediate', commission: 9_844,
  debitNoteNumber: 'DN-2026-00001',
  surveyRequired: false,
  clauses: [
    { id: 'c1', title: 'Third Party Liability',   text: 'Indemnity for third party bodily injury and property damage as per the Motor Vehicles (Third Party Insurance) Act.' },
    { id: 'c2', title: 'Own Damage',               text: 'Covers accidental damage to the insured vehicle including fire, theft and malicious damage.' },
    { id: 'c3', title: 'Exclusion — Racing',       text: 'This policy does not cover loss or damage arising from or whilst the vehicle is used in racing, rallying or similar events.' },
  ],
};

const statusVariant: Record<string, 'active'|'pending'|'draft'|'cancelled'|'rejected'> = {
  ACTIVE: 'active', PENDING_APPROVAL: 'pending', DRAFT: 'draft', EXPIRED: 'cancelled', CANCELLED: 'rejected', LAPSED: 'draft',
};

function Row({ label, value }: { label: string; value?: string }) {
  return (
    <div className="flex items-start gap-4 py-2.5" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-40 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value ?? '—'}</p>
    </div>
  );
}

function NaicomStatus({ uid, label }: { uid?: string; label: string }) {
  return (
    <div className="flex items-center justify-between rounded-lg border p-3">
      <div>
        <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{label}</p>
        {uid
          ? <p className="mt-1 font-mono text-sm font-medium text-foreground">{uid}</p>
          : <p className="mt-1 text-xs text-muted-foreground">Upload pending — auto-retried every 5 min</p>
        }
      </div>
      <Badge variant={uid ? 'active' : 'pending'} className="text-[10px]">
        {uid ? 'Uploaded' : 'Pending'}
      </Badge>
    </div>
  );
}

export default function PolicyDetailPage() {
  const navigate  = useNavigate();
  const p = mockPolicy;

  const canSubmit  = p.status === 'DRAFT';
  const canApprove = p.status === 'PENDING_APPROVAL';
  const isActive   = p.status === 'ACTIVE';

  return (
    <div className="p-6 space-y-5 max-w-5xl">
      <PageHeader
        title={p.policyNumber}
        description={`${p.productName} · ${p.customerName} · ${p.startDate} → ${p.endDate}`}
        breadcrumb={
          <button onClick={() => navigate('/policies')} className="text-sm text-muted-foreground hover:text-foreground">
            ← Policies
          </button>
        }
        actions={
          <div className="flex items-center gap-2 flex-wrap">
            <Badge variant={statusVariant[p.status]}>{p.status.toLowerCase().replace('_', ' ')}</Badge>
            {canSubmit  && <Button size="sm">Submit for Approval</Button>}
            {canApprove && <Button size="sm" variant="outline">Reject</Button>}
            {canApprove && <Button size="sm">Approve Policy</Button>}
            {isActive   && <Button size="sm" variant="outline">Add Endorsement</Button>}
            {isActive   && <Button size="sm">Register Claim</Button>}
            {p.documentPath && <Button size="sm" variant="outline">Download PDF</Button>}
          </div>
        }
      />

      <Tabs defaultValue="details">
        <TabsList>
          <TabsTrigger value="details">Details</TabsTrigger>
          <TabsTrigger value="document">Document</TabsTrigger>
          <TabsTrigger value="financial">Financial</TabsTrigger>
          <TabsTrigger value="survey">Survey</TabsTrigger>
          <TabsTrigger value="naicom">NAICOM / NIID</TabsTrigger>
        </TabsList>

        {/* ── Details ───────────────────────────────────────────────────── */}
        <TabsContent value="details" className="mt-4">
          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader><CardTitle>Policy Details</CardTitle></CardHeader>
              <CardContent>
                <Row label="Customer"      value={p.customerName} />
                <Row label="Product"       value={p.productName} />
                <Row label="Class"         value={p.classOfBusinessName} />
                <Row label="Business Type" value={p.businessType.replace(/_/g, ' ')} />
                <Row label="Period"        value={`${p.startDate} → ${p.endDate}`} />
                <Row label="Risk"          value={p.riskDescription} />
                <Row label="Quote Ref."    value={p.quoteId ?? 'Direct'} />
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle>Premium & Payment</CardTitle></CardHeader>
              <CardContent>
                <Row label="Sum Insured"    value={`₦${p.sumInsured.toLocaleString()}`} />
                <Row label="Gross Premium"  value={`₦${p.premium.toLocaleString()}`} />
                <Row label="Net Premium"    value={`₦${p.netPremium.toLocaleString()}`} />
                <Row label="Commission"     value={`₦${p.commission.toLocaleString()}`} />
                <Row label="Payment Terms"  value={p.paymentTerms} />
                <Row label="Debit Note"     value={p.debitNoteNumber} />
              </CardContent>
            </Card>
          </div>
        </TabsContent>

        {/* ── Document ─────────────────────────────────────────────────── */}
        <TabsContent value="document" className="mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Policy Document</CardTitle>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm">Edit Template</Button>
                  {p.documentPath && <Button size="sm">Download PDF</Button>}
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* Clause bank */}
              <p className="text-sm font-semibold text-foreground">Clauses</p>
              <div className="space-y-3">
                {p.clauses.map((clause) => (
                  <div key={clause.id} className="rounded-lg border p-4 space-y-1">
                    <div className="flex items-center justify-between">
                      <p className="text-sm font-semibold text-foreground">{clause.title}</p>
                      <Button variant="ghost" size="sm" className="h-7 text-xs text-muted-foreground">Edit</Button>
                    </div>
                    <p className="text-sm text-muted-foreground leading-relaxed">{clause.text}</p>
                  </div>
                ))}
                <Button variant="outline" size="sm">+ Add Clause</Button>
              </div>
              <Separator />
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-foreground">Document Status</p>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {p.documentPath ? 'PDF generated and ready to send' : 'Not yet generated — approve policy to generate'}
                  </p>
                </div>
                <Badge variant={p.documentPath ? 'active' : 'draft'} className="text-[10px]">
                  {p.documentPath ? 'Generated' : 'Pending'}
                </Badge>
              </div>
              {p.documentPath && (
                <div className="flex gap-2">
                  <Button size="sm">Send to Insured</Button>
                  <Button size="sm" variant="outline">Acknowledge Receipt</Button>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Financial ────────────────────────────────────────────────── */}
        <TabsContent value="financial" className="mt-4">
          <Card>
            <CardHeader><CardTitle>Debit Note & Finance</CardTitle></CardHeader>
            <CardContent>
              {p.debitNoteId ? (
                <>
                  <Row label="Debit Note No."  value={p.debitNoteNumber} />
                  <Row label="Amount"           value={`₦${p.netPremium.toLocaleString()}`} />
                  <Row label="Commission"       value={`₦${p.commission.toLocaleString()}`} />
                  <Row label="Payment Status"   value="Outstanding" />
                  <Row label="Due Date"         value={p.startDate} />
                  <div className="mt-4">
                    <Button size="sm">Post Receipt</Button>
                  </div>
                </>
              ) : (
                <p className="text-sm text-muted-foreground">Debit note will be generated when the policy is approved.</p>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Survey ───────────────────────────────────────────────────── */}
        <TabsContent value="survey" className="mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Pre-Loss Survey</CardTitle>
                {p.surveyRequired && <Badge variant="pending" className="text-[10px]">{p.surveyStatus ?? 'PENDING'}</Badge>}
              </div>
            </CardHeader>
            <CardContent>
              {!p.surveyRequired ? (
                <div className="space-y-3">
                  <p className="text-sm text-muted-foreground">
                    No pre-loss survey is required for this policy based on the sum insured threshold.
                  </p>
                  <Button variant="outline" size="sm">Request Survey Anyway</Button>
                  <Button variant="outline" size="sm" className="ml-2">Override Survey Requirement</Button>
                </div>
              ) : (
                <div className="space-y-3">
                  <Row label="Survey Type"    value="External Surveyor" />
                  <Row label="Surveyor"       value="Maxwell & Partners Ltd" />
                  <Row label="Assigned Date"  value="2026-01-31" />
                  <Row label="Report Status"  value="Pending submission" />
                  <div className="mt-4 flex gap-2">
                    <Button size="sm">Upload Survey Report</Button>
                    <Button size="sm" variant="outline">Approve Survey</Button>
                    <Button size="sm" variant="outline">Override</Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── NAICOM / NIID ─────────────────────────────────────────────── */}
        <TabsContent value="naicom" className="mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Regulatory Upload</CardTitle>
                <Button variant="outline" size="sm">Trigger Manual Upload</Button>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <NaicomStatus uid={p.naicomUid} label="NAICOM UID" />
              {(p.classOfBusinessName === 'Motor (Private)' || p.classOfBusinessName === 'Marine Cargo') && (
                <NaicomStatus uid={p.niidUid} label="NIID UID" />
              )}
              <div className="rounded-lg bg-muted/40 p-3">
                <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Upload Log</p>
                <div className="mt-2 space-y-1.5">
                  <p className="text-xs text-foreground">2026-02-01 08:14 — Upload succeeded · NAICOM returned UID NMC-2026-00001</p>
                  <p className="text-xs text-muted-foreground">2026-02-01 07:59 — First attempt failed (timeout) · Retried after 5 min</p>
                </div>
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
