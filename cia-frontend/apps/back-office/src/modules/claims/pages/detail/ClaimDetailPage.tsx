import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader,
  Separator, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import type { ClaimDto, ClaimReserveDto, ClaimExpenseDto } from '@cia/api-client';

// ── Mock data ─────────────────────────────────────────────────────────────
type MockClaim = ClaimDto & {
  policyProduct: string;
  natureOfLoss: string;
  causeOfLoss: string;
  location: string;
  contactName: string;
  contactPhone: string;
  comments: { id: string; user: string; date: string; text: string }[];
  requiredDocs: { id: string; name: string; received: boolean; receivedDate?: string }[];
  dvType?: 'OWN_DAMAGE' | 'THIRD_PARTY' | 'EX_GRATIA';
  dvAmount?: number;
  dvExecuted?: boolean;
};

const mockClaim: MockClaim = {
  id: 'cl1', claimNumber: 'CLM-2026-00001',
  policyId: 'pol1', policyNumber: 'POL-2026-00001',
  customerId: 'c1', customerName: 'Chioma Okafor',
  policyProduct: 'Private Motor Comprehensive',
  status: 'PROCESSING', incidentDate: '2026-03-10', registeredDate: '2026-03-12',
  description: 'Rear-end collision at Ozumba Mbadiwe Ave. by a bus. Vehicle sustained damage to rear bumper, boot lid and tail lights.',
  location: 'Ozumba Mbadiwe Ave, Victoria Island, Lagos',
  estimatedLoss: 850_000, reserveAmount: 650_000, paidAmount: 0,
  natureOfLoss: 'Own Damage',
  causeOfLoss: 'Accident',
  contactName: 'Chioma Okafor',
  contactPhone: '+234 803 111 0001',
  surveyorId: 'sv1', surveyorName: 'Maxwell & Partners',
  createdAt: '2026-03-12', updatedAt: '2026-03-15',
  comments: [
    { id: 'cmt1', user: 'Adaeze Nwosu', date: '2026-03-12', text: 'Claim registered. Assessor assigned to conduct pre-loss inspection report.' },
    { id: 'cmt2', user: 'Maxwell & Partners', date: '2026-03-14', text: 'Inspection completed. Repair estimate from Mercedes dealer: ₦720,000. Advising reserve of ₦650,000 pending final report.' },
  ],
  requiredDocs: [
    { id: 'd1', name: 'Police Report',        received: true,  receivedDate: '2026-03-13' },
    { id: 'd2', name: 'Driver Licence',        received: true,  receivedDate: '2026-03-12' },
    { id: 'd3', name: 'Vehicle Registration',  received: true,  receivedDate: '2026-03-12' },
    { id: 'd4', name: 'Repair Estimate',       received: true,  receivedDate: '2026-03-14' },
    { id: 'd5', name: 'Survey Report',         received: false  },
    { id: 'd6', name: 'Completed Claim Form',  received: false  },
  ],
  dvType: undefined, dvAmount: undefined, dvExecuted: false,
};

const mockReserves: ClaimReserveDto[] = [
  { id: 'r1', claimId: 'cl1', category: 'Own Damage — Vehicle Repairs', amount: 600_000, createdAt: '2026-03-12' },
  { id: 'r2', claimId: 'cl1', category: 'Survey Fees',                   amount: 50_000,  createdAt: '2026-03-12' },
];

const mockExpenses: ClaimExpenseDto[] = [
  { id: 'e1', claimId: 'cl1', type: 'Survey / Assessment Fee', amount: 35_000, status: 'APPROVED', createdAt: '2026-03-14' },
];

const statusVariant: Record<string, 'active'|'pending'|'draft'|'rejected'|'cancelled'> = {
  REGISTERED: 'draft', PROCESSING: 'pending', PENDING_APPROVAL: 'pending',
  APPROVED: 'active', REJECTED: 'rejected', SETTLED: 'active', CLOSED: 'cancelled', WITHDRAWN: 'cancelled',
};

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2.5" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-44 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

type DvType = 'OWN_DAMAGE' | 'THIRD_PARTY' | 'EX_GRATIA';
const DV_LABELS: Record<DvType, string> = {
  OWN_DAMAGE:   'Own Damage DV',
  THIRD_PARTY:  'Third Party DV',
  EX_GRATIA:    'Ex-gratia DV',
};

export default function ClaimDetailPage() {
  const navigate = useNavigate();
  const c = mockClaim;
  const [dvType,  setDvType]  = useState<DvType | ''>('');
  const [dvAmount,setDvAmount]= useState('');
  const [dvGenerated, setDvGenerated] = useState(false);

  const missingDocs = c.requiredDocs.filter(d => !d.received);
  const canSubmit   = c.status === 'PROCESSING';
  const canApprove  = c.status === 'PENDING_APPROVAL';
  const totalReserve = mockReserves.reduce((s, r) => s + r.amount, 0);
  const totalExpenses = mockExpenses.reduce((s, e) => s + e.amount, 0);

  return (
    <div className="p-6 space-y-5 max-w-5xl">
      <PageHeader
        title={c.claimNumber}
        description={`${c.policyProduct} · ${c.customerName} · Incident ${c.incidentDate}`}
        breadcrumb={
          <button onClick={() => navigate('/claims')} className="text-sm text-muted-foreground hover:text-foreground">
            ← Claims
          </button>
        }
        actions={
          <div className="flex items-center gap-2 flex-wrap">
            <Badge variant={statusVariant[c.status]}>{c.status.toLowerCase().replace('_', ' ')}</Badge>
            {missingDocs.length > 0 && (
              <Badge variant="pending" className="text-[10px]">{missingDocs.length} doc(s) missing</Badge>
            )}
            {canSubmit  && <Button size="sm">Submit for Approval</Button>}
            {canApprove && <Button size="sm" variant="outline">Reject</Button>}
            {canApprove && <Button size="sm">Approve Claim</Button>}
          </div>
        }
      />

      <Tabs defaultValue="summary">
        <TabsList>
          <TabsTrigger value="summary">Summary</TabsTrigger>
          <TabsTrigger value="processing">
            Processing
            {missingDocs.length > 0 && (
              <span className="ml-1.5 rounded-full bg-[var(--status-pending-bg)] text-[var(--status-pending-fg)] px-1.5 py-0.5 text-[10px] font-semibold">
                {missingDocs.length}
              </span>
            )}
          </TabsTrigger>
          <TabsTrigger value="documents">Documents</TabsTrigger>
          <TabsTrigger value="inspection">Inspection</TabsTrigger>
          <TabsTrigger value="dv">DV</TabsTrigger>
        </TabsList>

        {/* ── Summary ─────────────────────────────────────────────────── */}
        <TabsContent value="summary" className="mt-4">
          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader><CardTitle>Incident Details</CardTitle></CardHeader>
              <CardContent>
                <Row label="Policy"          value={`${c.policyNumber} · ${c.policyProduct}`} />
                <Row label="Customer"        value={c.customerName} />
                <Row label="Incident Date"   value={c.incidentDate} />
                <Row label="Registered"      value={c.registeredDate} />
                <Row label="Nature of Loss"  value={c.natureOfLoss} />
                <Row label="Cause of Loss"   value={c.causeOfLoss} />
                <Row label="Location"        value={c.location} />
                <Row label="Contact"         value={`${c.contactName} · ${c.contactPhone}`} />
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle>Financial Summary</CardTitle></CardHeader>
              <CardContent>
                <Row label="Estimated Loss"  value={`₦${c.estimatedLoss.toLocaleString()}`} />
                <Row label="Total Reserve"   value={`₦${totalReserve.toLocaleString()}`} />
                <Row label="Total Expenses"  value={`₦${totalExpenses.toLocaleString()}`} />
                <Row label="Paid Amount"     value={c.paidAmount > 0 ? `₦${c.paidAmount.toLocaleString()}` : '—'} />
                <Separator className="my-3" />
                <div className="flex items-center justify-between">
                  <p className="text-sm text-muted-foreground">Outstanding Reserve</p>
                  <p className="text-lg font-semibold text-foreground">₦{(totalReserve - c.paidAmount).toLocaleString()}</p>
                </div>
              </CardContent>
            </Card>
          </div>

          {/* Description */}
          <Card className="mt-4">
            <CardHeader><CardTitle>Incident Description</CardTitle></CardHeader>
            <CardContent>
              <p className="text-sm text-foreground leading-relaxed">{c.description}</p>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Processing ───────────────────────────────────────────────── */}
        <TabsContent value="processing" className="mt-4 space-y-4">
          {/* Reserves */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Reserves</CardTitle>
                <Button size="sm" variant="outline">Add Reserve</Button>
              </div>
            </CardHeader>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <thead><tr className="border-b bg-muted/40">
                  {['Category','Amount','Date'].map(h => <th key={h} className="h-9 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>)}
                </tr></thead>
                <tbody>
                  {mockReserves.map((r, i) => (
                    <tr key={r.id} className={i < mockReserves.length - 1 ? 'border-b' : ''}>
                      <td className="px-4 py-3">{r.category}</td>
                      <td className="px-4 py-3 font-medium tabular-nums">₦{r.amount.toLocaleString()}</td>
                      <td className="px-4 py-3 text-muted-foreground">{r.createdAt}</td>
                    </tr>
                  ))}
                  <tr className="border-t bg-muted/20">
                    <td className="px-4 py-2 font-semibold text-sm">Total</td>
                    <td className="px-4 py-2 font-semibold tabular-nums text-primary">₦{totalReserve.toLocaleString()}</td>
                    <td />
                  </tr>
                </tbody>
              </table>
            </CardContent>
          </Card>

          {/* Expenses */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Claim Expenses</CardTitle>
                <Button size="sm" variant="outline">Add Expense</Button>
              </div>
            </CardHeader>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <thead><tr className="border-b bg-muted/40">
                  {['Type','Amount','Status','Date'].map(h => <th key={h} className="h-9 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>)}
                </tr></thead>
                <tbody>
                  {mockExpenses.map((e, i) => (
                    <tr key={e.id} className={i < mockExpenses.length - 1 ? 'border-b' : ''}>
                      <td className="px-4 py-3">{e.type}</td>
                      <td className="px-4 py-3 font-medium tabular-nums">₦{e.amount.toLocaleString()}</td>
                      <td className="px-4 py-3">
                        <Badge variant={e.status === 'APPROVED' ? 'active' : 'pending'} className="text-[10px]">
                          {e.status.toLowerCase()}
                        </Badge>
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">{e.createdAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>

          {/* Comments */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Comments</CardTitle>
                <Button size="sm" variant="outline">Add Comment</Button>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              {c.comments.map((cmt) => (
                <div key={cmt.id} className="rounded-lg bg-muted/40 p-4 space-y-1">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-semibold text-foreground">{cmt.user}</p>
                    <p className="text-xs text-muted-foreground">{cmt.date}</p>
                  </div>
                  <p className="text-sm text-foreground leading-relaxed">{cmt.text}</p>
                </div>
              ))}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Documents ────────────────────────────────────────────────── */}
        <TabsContent value="documents" className="mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Required Documents</CardTitle>
                {missingDocs.length > 0 && (
                  <Badge variant="pending" className="text-xs">{missingDocs.length} outstanding</Badge>
                )}
              </div>
            </CardHeader>
            <CardContent className="space-y-2">
              {c.requiredDocs.map((doc) => (
                <div
                  key={doc.id}
                  className="flex items-center justify-between rounded-lg border p-3"
                  style={!doc.received ? { background: 'var(--status-pending-bg)' } : undefined}
                >
                  <div className="flex items-center gap-3">
                    <div className={`h-5 w-5 rounded-full flex items-center justify-center text-xs ${doc.received ? 'bg-primary text-primary-foreground' : 'border-2 border-[var(--status-pending-fg)]'}`}>
                      {doc.received && '✓'}
                    </div>
                    <p className="text-sm font-medium text-foreground">{doc.name}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    {doc.received
                      ? <p className="text-xs text-muted-foreground">{doc.receivedDate}</p>
                      : <Button size="sm" variant="outline" className="h-7 text-xs">Upload</Button>
                    }
                  </div>
                </div>
              ))}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Loss Inspection ──────────────────────────────────────────── */}
        <TabsContent value="inspection" className="mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Loss Inspection</CardTitle>
                <Badge variant={c.surveyorId ? 'active' : 'draft'} className="text-[10px]">
                  {c.surveyorId ? 'Assigned' : 'Not assigned'}
                </Badge>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              {c.surveyorId ? (
                <>
                  <Row label="Surveyor"      value={c.surveyorName ?? '—'} />
                  <Row label="Type"          value="External Surveyor" />
                  <Row label="Assigned Date" value="2026-03-12" />
                  <Row label="Report Status" value="Submitted — awaiting claim officer review" />
                  <div className="flex gap-2 mt-4">
                    <Button size="sm">Approve Inspection Report</Button>
                    <Button size="sm" variant="outline">Override Requirement</Button>
                    <Button size="sm" variant="outline">Download Report</Button>
                  </div>
                </>
              ) : (
                <div className="space-y-4">
                  <p className="text-sm text-muted-foreground">No surveyor assigned yet.</p>
                  <div className="flex gap-2">
                    <Button size="sm">Assign Internal Surveyor</Button>
                    <Button size="sm" variant="outline">Assign External Surveyor</Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── DV (Discharge Voucher) ────────────────────────────────────── */}
        <TabsContent value="dv" className="mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Discharge Voucher (DV)</CardTitle>
                {dvGenerated && <Badge variant="active" className="text-[10px]">Generated</Badge>}
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              {!c.status.includes('APPROVED') && !dvGenerated ? (
                <div className="rounded-lg bg-muted/40 p-4">
                  <p className="text-sm text-muted-foreground">
                    DV can only be generated once the claim is approved. Current status: <strong>{c.status}</strong>
                  </p>
                </div>
              ) : dvGenerated ? (
                <div className="space-y-3">
                  <Row label="DV Type"   value={dvType ? DV_LABELS[dvType as DvType] : '—'} />
                  <Row label="DV Amount" value={dvAmount ? `₦${Number(dvAmount).toLocaleString()}` : '—'} />
                  <Row label="Status"    value="Generated — awaiting execution" />
                  <div className="flex gap-2 mt-2">
                    <Button size="sm">Execute DV (Online Portal)</Button>
                    <Button size="sm" variant="outline">Download DV</Button>
                  </div>
                </div>
              ) : (
                <div className="space-y-4">
                  <p className="text-sm font-semibold text-foreground">Generate Discharge Voucher</p>
                  <div className="grid grid-cols-2 gap-4">
                    {(['OWN_DAMAGE', 'THIRD_PARTY', 'EX_GRATIA'] as DvType[]).map((type) => (
                      <button
                        key={type}
                        onClick={() => setDvType(type)}
                        className={`rounded-lg border p-4 text-left transition-colors ${dvType === type ? 'bg-teal-50 border-primary' : 'bg-card hover:bg-secondary'}`}
                      >
                        <p className="text-sm font-semibold text-foreground">{DV_LABELS[type]}</p>
                        <p className="text-xs text-muted-foreground mt-1">
                          {type === 'OWN_DAMAGE' ? 'Payment to insured for own vehicle/property damage'
                            : type === 'THIRD_PARTY' ? 'Payment for third party bodily injury or property damage'
                            : 'Discretionary payment outside policy terms'}
                        </p>
                      </button>
                    ))}
                  </div>
                  {dvType && (
                    <div className="flex gap-3 items-end">
                      <div className="flex-1 space-y-1.5">
                        <label className="text-sm font-medium text-foreground">DV Amount (₦)</label>
                        <input
                          type="number"
                          value={dvAmount}
                          onChange={(e) => setDvAmount(e.target.value)}
                          placeholder="Enter DV amount"
                          className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                        />
                      </div>
                      <Button
                        size="sm"
                        disabled={!dvAmount || Number(dvAmount) <= 0}
                        onClick={() => setDvGenerated(true)}
                      >
                        Generate DV
                      </Button>
                    </div>
                  )}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
