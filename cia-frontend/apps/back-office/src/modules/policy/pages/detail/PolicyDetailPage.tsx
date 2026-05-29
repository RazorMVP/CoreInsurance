import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader,
  Separator, Skeleton, Tabs, TabsContent, TabsList, TabsTrigger,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Label, Textarea, toast,
} from '@cia/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient, type ApiError, type ApiResponse, type PolicyDto,
  type DebitNoteDto, type CreditNoteDto, type ReceiptDto,
} from '@cia/api-client';
import AssignSurveyorDialog       from './AssignSurveyorDialog';
import SubmitSurveyReportDialog   from './SubmitSurveyReportDialog';
import CoinsuranceEditorDialog    from './CoinsuranceEditorDialog';
import RisksEditorDialog          from './RisksEditorDialog';
import PostReceiptDialog          from './PostReceiptDialog';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

function showServerError(err: unknown, title: string) {
  const ax = err as ApiHttpError;
  const errors: ApiError[] = ax?.response?.data?.errors ?? [];
  const description = errors.length > 0
    ? errors.map(e => e.message).filter(Boolean).join('. ')
    : ax?.message ?? 'An unexpected error occurred. Please try again.';
  toast({ variant: 'destructive', title, description });
}

type MockPolicy = PolicyDto & {
  riskDescription: string;
  paymentTerms: string;
  surveyRequired: boolean;
  clauses: { id: string; title: string; text: string }[];
};

// Display labels for the V50 CommissionSourceType enum, mirroring CommissionSetupsSheet.
const COMMISSION_SOURCE_LABEL: Record<NonNullable<PolicyDto['commissionSourceType']>, string> = {
  AGENT:                'Agent',
  BROKER:               'Broker',
  RELATIONSHIP_MANAGER: 'Relationship Manager',
};

// Badge variant maps for the live debit-note + credit-note status pills.
const dnStatusVariant: Record<DebitNoteDto['status'], 'pending' | 'active' | 'draft' | 'rejected'> = {
  OUTSTANDING: 'pending',
  PARTIAL:     'draft',
  SETTLED:     'active',
  CANCELLED:   'rejected',
  VOID:        'rejected',
};
const cnStatusVariant: Record<CreditNoteDto['status'], 'pending' | 'active' | 'draft' | 'rejected'> = {
  OUTSTANDING: 'pending',
  PARTIAL:     'draft',
  SETTLED:     'active',
  CANCELLED:   'rejected',
};

// allow-mock: fallback while useQuery is in flight or for unknown ids
const mockPolicy: MockPolicy = {
  id: 'pol1', policyNumber: 'POL-2026-00001', status: 'ACTIVE',
  quoteId: 'q4', quoteNumber: 'Q-2026-00004',
  customerId: 'c1', customerName: 'Chioma Okafor',
  productId: 'p1', productName: 'Private Motor Comprehensive', productCode: 'PMC', productRate: 2.25,
  classOfBusinessId: '1', classOfBusinessName: 'Motor (Private)', classOfBusinessCode: 'MOTOR',
  businessType: 'DIRECT', niidRequired: true,
  policyStartDate: '2026-02-01', policyEndDate: '2027-02-01',
  totalSumInsured: 3_500_000, totalPremium: 78_750, discount: 0, netPremium: 78_750,
  naicomUid: 'NMC-2026-00001', niidRef: 'NIID-2026-00001',
  policyDocumentPath: '/docs/pol1.pdf',
  risks: [], coinsuranceParticipants: [], survey: null,
  createdAt: '2026-01-30',
  riskDescription: '2022 Toyota Camry 2.5L, Reg: LND-001-AA, Chassis: ABC123',
  paymentTerms: 'Immediate',
  surveyRequired: false,
  clauses: [
    { id: 'c1', title: 'Third Party Liability',   text: 'Indemnity for third party bodily injury and property damage as per the Motor Vehicles (Third Party Insurance) Act.' },
    { id: 'c2', title: 'Own Damage',               text: 'Covers accidental damage to the insured vehicle including fire, theft and malicious damage.' },
    { id: 'c3', title: 'Exclusion — Racing',       text: 'This policy does not cover loss or damage arising from or whilst the vehicle is used in racing, rallying or similar events.' },
  ],
};

const statusVariant: Record<PolicyDto['status'], 'active' | 'pending' | 'draft' | 'cancelled' | 'rejected'> = {
  ACTIVE:           'active',
  REINSTATED:       'active',
  PENDING_APPROVAL: 'pending',
  DRAFT:            'draft',
  EXPIRED:          'cancelled',
  CANCELLED:        'rejected',
  REJECTED:         'rejected',
  LAPSED:           'draft',
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
  const navigate    = useNavigate();
  const { id }      = useParams<{ id: string }>();
  const queryClient = useQueryClient();

  const policyQuery = useQuery<MockPolicy>({
    queryKey: ['policies', id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: MockPolicy }>(`/api/v1/policies/${id}`);
      return res.data.data;
    },
    enabled: !!id,
  });

  // Fall back to local mock while loading or for unknown ids — keeps the
  // page renderable mid-prototype while the backend wires up.
  const p = policyQuery.data ?? mockPolicy;

  // ─── Mutations (B5.2 — wire B4 endpoints) ─────────────────────────────
  const onSuccess = (title: string) => () => {
    queryClient.invalidateQueries({ queryKey: ['policies', id] });
    toast({ title });
  };

  const submit  = useMutation({
    mutationFn: () => apiClient.post(`/api/v1/policies/${id}/submit`),
    onSuccess: onSuccess('Submitted for approval'),
    onError:   (e) => showServerError(e, 'Could not submit policy'),
  });
  const approve = useMutation({
    mutationFn: () => apiClient.post(`/api/v1/policies/${id}/approve`),
    onSuccess: onSuccess('Policy approved'),
    onError:   (e) => showServerError(e, 'Could not approve policy'),
  });
  const reject = useMutation({
    mutationFn: () => apiClient.post(`/api/v1/policies/${id}/reject`),
    onSuccess: onSuccess('Policy rejected'),
    onError:   (e) => showServerError(e, 'Could not reject policy'),
  });
  const sendDoc = useMutation({
    mutationFn: () => apiClient.post(`/api/v1/policies/${id}/document/send`),
    onSuccess: onSuccess('Policy document sent to insured'),
    onError:   (e) => showServerError(e, 'Could not send policy document'),
  });
  const ackDoc = useMutation({
    mutationFn: () => apiClient.post(`/api/v1/policies/${id}/document/acknowledge`),
    onSuccess: onSuccess('Receipt acknowledged'),
    onError:   (e) => showServerError(e, 'Could not record acknowledgement'),
  });
  const naicom = useMutation({
    mutationFn: () => apiClient.post(`/api/v1/policies/${id}/naicom-upload`),
    onSuccess: onSuccess('NAICOM upload triggered'),
    onError:   (e) => showServerError(e, 'Could not trigger NAICOM upload'),
  });
  const niid = useMutation({
    mutationFn: () => apiClient.post(`/api/v1/policies/${id}/niid-upload`),
    onSuccess: onSuccess('NIID upload triggered'),
    onError:   (e) => showServerError(e, 'Could not trigger NIID upload'),
  });
  const approveSurvey = useMutation({
    mutationFn: () => apiClient.post(`/api/v1/policies/${id}/survey/approve`),
    onSuccess: onSuccess('Survey approved'),
    onError:   (e) => showServerError(e, 'Could not approve survey'),
  });

  // Override-survey dialog
  const [overrideOpen,    setOverrideOpen]    = useState(false);
  const [overrideReason,  setOverrideReason]  = useState('');
  const [overrideErr,     setOverrideErr]     = useState<string | null>(null);
  useEffect(() => { if (!overrideOpen) { setOverrideReason(''); setOverrideErr(null); } }, [overrideOpen]);

  // B5.3 dialogs
  const [assignSurveyorOpen,  setAssignSurveyorOpen]  = useState(false);
  const [submitReportOpen,    setSubmitReportOpen]    = useState(false);
  const [coinsuranceOpen,     setCoinsuranceOpen]     = useState(false);
  const [risksEditorOpen,     setRisksEditorOpen]     = useState(false);

  // Slice 96 / Backlog C1 — Finance tab queries.
  // Backend exposes ?entityId=<policyId> for both debit-notes (Session 96
  // added the filter) and credit-notes (CreditNoteController already had it).
  // For a policy the lists return at most one DN (premium receivable) and at
  // most one CN (broker / agent commission payable) — we take the first.
  // Receipts are queried via the nested endpoint once the DN id is known.
  const debitNoteQuery = useQuery<DebitNoteDto | null>({
    queryKey: ['policy-debit-note', id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: DebitNoteDto[] }>('/api/v1/debit-notes', { params: { entityId: id } });
      return res.data.data[0] ?? null;
    },
    enabled: !!id,
  });
  const policyDn = debitNoteQuery.data;

  const commissionCnQuery = useQuery<CreditNoteDto | null>({
    queryKey: ['policy-commission-cn', id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CreditNoteDto[] }>('/api/v1/credit-notes', { params: { entityId: id } });
      // Filter to POLICY-typed CNs — backend list endpoint doesn't filter by
      // entityType, and a policy id is unique so practically only the
      // commission CN matches. Belt-and-braces here in case future code
      // emits other CN types against the same entityId.
      return res.data.data.find(cn => cn.entityType === 'POLICY') ?? null;
    },
    enabled: !!id,
  });
  const commissionCn = commissionCnQuery.data;

  const receiptsQuery = useQuery<ReceiptDto[]>({
    queryKey: ['policy-receipts', policyDn?.id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ReceiptDto[] }>(`/api/v1/debit-notes/${policyDn!.id}/receipts`);
      return res.data.data;
    },
    enabled: !!policyDn?.id,
  });
  const receipts = receiptsQuery.data ?? [];

  const [postReceiptOpen, setPostReceiptOpen] = useState(false);
  const overrideSurvey = useMutation({
    mutationFn: (reason: string) =>
      apiClient.post(`/api/v1/policies/${id}/survey/override`, { reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies', id] });
      toast({ title: 'Survey requirement overridden' });
      setOverrideOpen(false);
    },
    onError:   (e) => showServerError(e, 'Could not override survey'),
  });

  function downloadPdf() {
    apiClient.get(`/api/v1/policies/${id}/document`, { responseType: 'blob' })
      .then(res => {
        const blob = new Blob([res.data as Blob], { type: 'application/pdf' });
        const url  = URL.createObjectURL(blob);
        const a    = document.createElement('a');
        a.href = url; a.download = `${p.policyNumber ?? id}.pdf`;
        a.click();
        URL.revokeObjectURL(url);
      })
      .catch(e => showServerError(e, 'Could not download policy document'));
  }

  if (policyQuery.isLoading && !policyQuery.data) {
    return (
      <div className="p-6 space-y-4 max-w-5xl">
        <Skeleton className="h-9 w-72" />
        <Skeleton className="h-32 w-full rounded-lg" />
        <Skeleton className="h-64 w-full rounded-lg" />
      </div>
    );
  }

  const canSubmit  = p.status === 'DRAFT';
  const canApprove = p.status === 'PENDING_APPROVAL';
  const isActive   = p.status === 'ACTIVE';
  const niidEligible = p.classOfBusinessName === 'Motor (Private)' || p.classOfBusinessName === 'Marine Cargo';

  return (
    <div className="p-6 space-y-5 max-w-5xl">
      <PageHeader
        title={p.policyNumber ?? p.id}
        description={`${p.productName} · ${p.customerName} · ${p.policyStartDate} → ${p.policyEndDate}`}
        breadcrumb={
          <button onClick={() => navigate('/policies')} className="text-sm text-muted-foreground hover:text-foreground">
            ← Policies
          </button>
        }
        actions={
          <div className="flex items-center gap-2 flex-wrap">
            <Badge variant={statusVariant[p.status]}>{p.status.toLowerCase().replace('_', ' ')}</Badge>
            {canSubmit  && <Button size="sm" disabled={submit.isPending}  onClick={() => submit.mutate()}>{submit.isPending ? 'Submitting…' : 'Submit for Approval'}</Button>}
            {canApprove && <Button size="sm" variant="outline" disabled={reject.isPending}  onClick={() => reject.mutate()}>Reject</Button>}
            {canApprove && <Button size="sm" disabled={approve.isPending} onClick={() => approve.mutate()}>{approve.isPending ? 'Approving…' : 'Approve Policy'}</Button>}
            {isActive   && <Button size="sm" variant="outline" onClick={() => navigate('/endorsements/create')}>Add Endorsement</Button>}
            {isActive   && <Button size="sm" onClick={() => navigate('/claims/register')}>Register Claim</Button>}
            {p.policyDocumentPath && <Button size="sm" variant="outline" onClick={downloadPdf}>Download PDF</Button>}
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
        <TabsContent value="details" className="mt-4 space-y-4">
          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader><CardTitle>Policy Details</CardTitle></CardHeader>
              <CardContent>
                <Row label="Customer"      value={p.customerName} />
                <Row label="Product"       value={p.productName} />
                <Row label="Class"         value={p.classOfBusinessName} />
                <Row label="Business Type" value={p.businessType.replace(/_/g, ' ')} />
                <Row label="Period"        value={`${p.policyStartDate} → ${p.policyEndDate}`} />
                <Row label="Risk"          value={p.riskDescription} />
                <Row label="Quote Ref."    value={p.quoteNumber ?? p.quoteId ?? 'Direct'} />
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle>Premium & Payment</CardTitle></CardHeader>
              <CardContent>
                <Row label="Sum Insured"    value={`₦${p.totalSumInsured.toLocaleString()}`} />
                <Row label="Gross Premium"  value={`₦${p.totalPremium.toLocaleString()}`} />
                <Row label="Net Premium"    value={`₦${p.netPremium.toLocaleString()}`} />
                <Row
                  label="Intermediary"
                  value={
                    p.brokerName
                      ? `Broker · ${p.brokerName}`
                      : p.agentName
                        ? `Agent · ${p.agentName}`
                        : 'Direct'
                  }
                />
                <Row
                  label="Commission"
                  value={
                    p.commissionAmount != null
                      ? `₦${p.commissionAmount.toLocaleString()}`
                      : undefined
                  }
                />
                <Row label="Payment Terms"  value={p.paymentTerms} />
                <Row label="Debit Note"     value={policyDn?.debitNoteNumber} />
              </CardContent>
            </Card>
          </div>

          {/* ── Risk schedule (B5.3d) ─────────────────────────────────── */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Risk Schedule</CardTitle>
                <Button size="sm" variant="outline" onClick={() => setRisksEditorOpen(true)}>
                  Edit Risks
                </Button>
              </div>
            </CardHeader>
            <CardContent className="p-0">
              {p.risks.length === 0 ? (
                <p className="text-sm text-muted-foreground py-6 text-center">
                  No risks recorded. Click Edit Risks to add line items.
                </p>
              ) : (
                <table className="w-full text-sm">
                  <thead><tr className="border-b bg-muted/40">
                    {['Description', 'Reg No.', 'Sum Insured', 'Premium'].map(h => (
                      <th key={h} className="h-9 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
                    ))}
                  </tr></thead>
                  <tbody>
                    {p.risks.map((r, i) => (
                      <tr key={r.id} className={i < p.risks.length - 1 ? 'border-b' : ''}>
                        <td className="px-4 py-3">{r.description}</td>
                        <td className="px-4 py-3 text-muted-foreground">{r.vehicleRegNumber ?? '—'}</td>
                        <td className="px-4 py-3 font-medium tabular-nums">₦{r.sumInsured.toLocaleString()}</td>
                        <td className="px-4 py-3 font-medium tabular-nums">₦{r.premium.toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </CardContent>
          </Card>

          {/* ── Coinsurance shares (B5.3c) — only relevant for coinsurance policies */}
          {(p.businessType === 'DIRECT_WITH_COINSURANCE' || p.businessType === 'INWARD_COINSURANCE') && (
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle>Coinsurance Participants</CardTitle>
                  <Button size="sm" variant="outline" onClick={() => setCoinsuranceOpen(true)}>
                    Edit Shares
                  </Button>
                </div>
              </CardHeader>
              <CardContent className="p-0">
                {p.coinsuranceParticipants.length === 0 ? (
                  <p className="text-sm text-muted-foreground py-6 text-center">
                    No participants recorded yet.
                  </p>
                ) : (
                  <table className="w-full text-sm">
                    <thead><tr className="border-b bg-muted/40">
                      {['Insurer', 'Share %'].map(h => (
                        <th key={h} className="h-9 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
                      ))}
                    </tr></thead>
                    <tbody>
                      {p.coinsuranceParticipants.map((cp, i) => (
                        <tr key={cp.id} className={i < p.coinsuranceParticipants.length - 1 ? 'border-b' : ''}>
                          <td className="px-4 py-3">{cp.insuranceCompanyName}</td>
                          <td className="px-4 py-3 font-medium tabular-nums">{cp.sharePercentage.toFixed(2)}%</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </CardContent>
            </Card>
          )}
        </TabsContent>

        {/* ── Document ─────────────────────────────────────────────────── */}
        <TabsContent value="document" className="mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Policy Document</CardTitle>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm">Edit Template</Button>
                  {p.policyDocumentPath && <Button size="sm" onClick={downloadPdf}>Download PDF</Button>}
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
                    {p.policyDocumentPath ? 'PDF generated and ready to send' : 'Not yet generated — approve policy to generate'}
                  </p>
                </div>
                <Badge variant={p.policyDocumentPath ? 'active' : 'draft'} className="text-[10px]">
                  {p.policyDocumentPath ? 'Generated' : 'Pending'}
                </Badge>
              </div>
              {p.policyDocumentPath && (
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    disabled={sendDoc.isPending || !!p.documentSentAt}
                    onClick={() => sendDoc.mutate()}
                  >
                    {p.documentSentAt
                      ? `Sent ${new Date(p.documentSentAt).toLocaleDateString()}`
                      : sendDoc.isPending ? 'Sending…' : 'Send to Insured'}
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={ackDoc.isPending || !p.documentSentAt || !!p.documentAcknowledgedAt}
                    onClick={() => ackDoc.mutate()}
                  >
                    {p.documentAcknowledgedAt
                      ? `Acknowledged ${new Date(p.documentAcknowledgedAt).toLocaleDateString()}`
                      : ackDoc.isPending ? 'Recording…' : 'Acknowledge Receipt'}
                  </Button>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Financial — wired against real cia-finance (Slice 96 / Backlog C1) ── */}
        <TabsContent value="financial" className="mt-4 space-y-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Debit Note &amp; Finance</CardTitle>
                {policyDn && (
                  <Badge variant={dnStatusVariant[policyDn.status]} className="text-xs">
                    {policyDn.status.toLowerCase().replace(/_/g, ' ')}
                  </Badge>
                )}
              </div>
            </CardHeader>
            <CardContent>
              {debitNoteQuery.isLoading ? (
                <Skeleton className="h-32 w-full" />
              ) : policyDn ? (
                <>
                  <Row label="Debit Note No." value={policyDn.debitNoteNumber} />
                  <Row label="Amount"          value={`₦${policyDn.totalAmount.toLocaleString()}`} />
                  <Row label="Paid"            value={`₦${policyDn.paidAmount.toLocaleString()}`} />
                  <Row label="Outstanding"     value={`₦${policyDn.outstandingAmount.toLocaleString()}`} />
                  <Row label="Due Date"        value={policyDn.dueDate} />
                  <div className="mt-4">
                    <Button
                      size="sm"
                      disabled={policyDn.status === 'SETTLED' || policyDn.status === 'CANCELLED' || policyDn.status === 'VOID'}
                      onClick={() => setPostReceiptOpen(true)}
                    >
                      Post Receipt
                    </Button>
                  </div>
                </>
              ) : (
                <p className="text-sm text-muted-foreground">Debit note will be generated when the policy is approved.</p>
              )}
            </CardContent>
          </Card>

          {/* ── Receipts against this debit note ─────────────────────────── */}
          {policyDn && (
            <Card>
              <CardHeader><CardTitle>Receipts</CardTitle></CardHeader>
              <CardContent className="p-0">
                {receiptsQuery.isLoading ? (
                  <div className="p-6"><Skeleton className="h-12 w-full" /></div>
                ) : receipts.length === 0 ? (
                  <p className="px-6 py-6 text-sm text-muted-foreground">No receipts posted yet.</p>
                ) : (
                  <table className="w-full text-sm">
                    <thead><tr className="border-b bg-muted/40">
                      {['Receipt No.', 'Date', 'Method', 'Amount', 'Posted By', 'Status'].map(h => (
                        <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
                      ))}
                    </tr></thead>
                    <tbody>
                      {receipts.map((r, i) => (
                        <tr key={r.id} className={i < receipts.length - 1 ? 'border-b' : ''}>
                          <td className="px-4 py-3 font-mono text-xs">{r.receiptNumber}</td>
                          <td className="px-4 py-3 text-xs text-muted-foreground">{r.paymentDate ?? r.createdAt.slice(0, 10)}</td>
                          <td className="px-4 py-3 text-xs">{r.paymentMethod.replace(/_/g, ' ')}</td>
                          <td className="px-4 py-3 font-medium tabular-nums">₦{r.amount.toLocaleString()}</td>
                          <td className="px-4 py-3 text-xs text-muted-foreground">{r.postedBy ?? '—'}</td>
                          <td className="px-4 py-3">
                            <Badge variant="outline" className="text-[10px]">{r.status.toLowerCase()}</Badge>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </CardContent>
            </Card>
          )}

          {/* ── Commission snapshot (Slice 84e) + live CN status (Slice 96) ── */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Commission</CardTitle>
                <div className="flex items-center gap-2">
                  {p.commissionSourceType && (
                    <Badge variant="outline">{COMMISSION_SOURCE_LABEL[p.commissionSourceType]}</Badge>
                  )}
                  {commissionCn && (
                    <Badge variant={cnStatusVariant[commissionCn.status]} className="text-xs">
                      {commissionCn.status.toLowerCase()}
                    </Badge>
                  )}
                </div>
              </div>
            </CardHeader>
            <CardContent>
              {p.commissionSourceType && p.commissionRate != null && p.commissionAmount != null ? (
                <>
                  <Row label="Source"     value={COMMISSION_SOURCE_LABEL[p.commissionSourceType]} />
                  <Row label="Rate"       value={`${p.commissionRate}%`} />
                  <Row label="Amount"     value={`₦${p.commissionAmount.toLocaleString()}`} />
                  {p.commissionSourceType === 'RELATIONSHIP_MANAGER' ? (
                    // RM commission is accrual-only (Dr 5130 / Cr 2520) — paid via
                    // payroll, never producing a credit note / finance payment.
                    <>
                      {p.relationshipManagerName && (
                        <Row label="Relationship Manager" value={p.relationshipManagerName} />
                      )}
                      <p className="mt-3 text-xs text-muted-foreground">
                        Relationship-manager commission is accrued to payroll (no credit note or
                        finance payment is raised).
                      </p>
                    </>
                  ) : commissionCn ? (
                    <>
                      <Row label="Credit Note No." value={commissionCn.creditNoteNumber} />
                      <Row label="Beneficiary"     value={commissionCn.beneficiaryName ?? undefined} />
                      <Row label="Paid"            value={`₦${commissionCn.paidAmount.toLocaleString()}`} />
                      <Row label="Outstanding"     value={`₦${commissionCn.outstandingAmount.toLocaleString()}`} />
                    </>
                  ) : (
                    <p className="mt-3 text-xs text-muted-foreground">
                      Credit note will appear here once the policy is approved.
                    </p>
                  )}
                </>
              ) : (
                <p className="text-sm text-muted-foreground">
                  No commission configured at issuance. Configure a commission rule under Setup &rarr; Products
                  before approving future policies to enable automatic commission credit-note generation.
                </p>
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
                {p.surveyRequired && <Badge variant="pending" className="text-[10px]">{p.survey?.status ?? 'PENDING'}</Badge>}
              </div>
            </CardHeader>
            <CardContent>
              {!p.surveyRequired ? (
                <div className="space-y-3">
                  <p className="text-sm text-muted-foreground">
                    No pre-loss survey is required for this policy based on the sum insured threshold.
                  </p>
                  <div className="flex gap-2">
                    <Button size="sm" variant="outline" onClick={() => setAssignSurveyorOpen(true)}>
                      Request Survey Anyway
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => setOverrideOpen(true)}>
                      Override Survey Requirement
                    </Button>
                  </div>
                </div>
              ) : !p.survey ? (
                <div className="space-y-3">
                  <p className="text-sm text-muted-foreground">
                    Survey required but no surveyor has been assigned yet.
                  </p>
                  <div className="flex gap-2">
                    <Button size="sm" onClick={() => setAssignSurveyorOpen(true)}>
                      Assign Surveyor
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => setOverrideOpen(true)}>
                      Override
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="space-y-3">
                  <Row label="Survey Type"    value={p.survey?.surveyorType ?? '—'} />
                  <Row label="Surveyor"       value={p.survey?.surveyorName ?? '—'} />
                  <Row label="Assigned Date"  value={p.survey?.assignedAt?.split('T')[0] ?? '—'} />
                  <Row label="Report Status"  value={p.survey?.status ?? 'Pending submission'} />
                  <div className="mt-4 flex gap-2 flex-wrap">
                    {p.survey.status === 'ASSIGNED' && (
                      <Button size="sm" onClick={() => setSubmitReportOpen(true)}>
                        Submit Report
                      </Button>
                    )}
                    {p.survey.status === 'REPORT_SUBMITTED' && (
                      <Button
                        size="sm"
                        disabled={approveSurvey.isPending}
                        onClick={() => approveSurvey.mutate()}
                      >
                        {approveSurvey.isPending ? 'Approving…' : 'Approve Survey'}
                      </Button>
                    )}
                    {p.survey.status !== 'APPROVED' && p.survey.status !== 'OVERRIDDEN' && (
                      <Button size="sm" variant="outline" onClick={() => setOverrideOpen(true)}>
                        Override
                      </Button>
                    )}
                    {p.survey.status === 'OVERRIDDEN' && (
                      <Button size="sm" variant="outline" onClick={() => setAssignSurveyorOpen(true)}>
                        Re-assign Surveyor
                      </Button>
                    )}
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
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={naicom.isPending || !isActive}
                    onClick={() => naicom.mutate()}
                  >
                    {naicom.isPending ? 'Uploading…' : 'NAICOM Upload'}
                  </Button>
                  {niidEligible && (
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={niid.isPending || !isActive}
                      onClick={() => niid.mutate()}
                    >
                      {niid.isPending ? 'Uploading…' : 'NIID Upload'}
                    </Button>
                  )}
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <NaicomStatus uid={p.naicomUid ?? undefined} label="NAICOM UID" />
              {(p.classOfBusinessName === 'Motor (Private)' || p.classOfBusinessName === 'Marine Cargo') && (
                <NaicomStatus uid={p.niidRef ?? undefined} label="NIID UID" />
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

      {/* Override Survey dialog */}
      <Dialog open={overrideOpen} onOpenChange={setOverrideOpen}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>Override Survey Requirement</DialogTitle>
            <DialogDescription>
              Waiving the pre-loss survey requirement is permanent and recorded against the policy.
              Provide a reason before confirming.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-1.5">
            <Label htmlFor="survey-override-reason">Reason</Label>
            <Textarea
              id="survey-override-reason"
              placeholder="e.g. Risk previously surveyed under prior policy / standard product / executive override"
              rows={3}
              value={overrideReason}
              onChange={e => { setOverrideReason(e.target.value); if (overrideErr) setOverrideErr(null); }}
              disabled={overrideSurvey.isPending}
            />
            {overrideErr && <p className="text-xs text-destructive">{overrideErr}</p>}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setOverrideOpen(false)} disabled={overrideSurvey.isPending}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={overrideSurvey.isPending}
              onClick={() => {
                if (overrideReason.trim().length < 5) {
                  setOverrideErr('Reason must be at least 5 characters.');
                  return;
                }
                overrideSurvey.mutate(overrideReason);
              }}
            >
              {overrideSurvey.isPending ? 'Overriding…' : 'Override'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AssignSurveyorDialog
        open={assignSurveyorOpen}
        onOpenChange={setAssignSurveyorOpen}
        policyId={p.id}
        policyNumber={p.policyNumber ?? p.id}
        onSuccess={() => setAssignSurveyorOpen(false)}
      />

      <SubmitSurveyReportDialog
        open={submitReportOpen}
        onOpenChange={setSubmitReportOpen}
        policyId={p.id}
        policyNumber={p.policyNumber ?? p.id}
        onSuccess={() => setSubmitReportOpen(false)}
      />

      <CoinsuranceEditorDialog
        open={coinsuranceOpen}
        onOpenChange={setCoinsuranceOpen}
        policyId={p.id}
        policyNumber={p.policyNumber ?? p.id}
        participants={p.coinsuranceParticipants}
        onSuccess={() => setCoinsuranceOpen(false)}
      />

      <RisksEditorDialog
        open={risksEditorOpen}
        onOpenChange={setRisksEditorOpen}
        policyId={p.id}
        policyNumber={p.policyNumber ?? p.id}
        risks={p.risks}
        isMotor={p.classOfBusinessName.toLowerCase().includes('motor')}
        onSuccess={() => setRisksEditorOpen(false)}
      />

      <PostReceiptDialog
        open={postReceiptOpen}
        onOpenChange={setPostReceiptOpen}
        debitNote={policyDn ?? null}
        onSuccess={() => setPostReceiptOpen(false)}
      />
    </div>
  );
}
