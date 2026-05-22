import { useNavigate, useParams } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader, Separator, Skeleton,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient, ENDORSEMENT_TYPE_LABELS, type EndorsementDto } from '@cia/api-client';

// allow-mock: fallback while useQuery is in flight or for unknown ids
const mockEndorsement: EndorsementDto = {
  id: 'end2', endorsementNumber: 'END-2026-00002',
  status: 'SUBMITTED',
  endorsementType: 'INCREASE_SI',
  policyId: 'pol1', policyNumber: 'POL-2026-00001',
  customerId: 'cust1', customerName: 'Chioma Okafor',
  productName: 'Motor Comprehensive',
  classOfBusinessName: 'Motor',
  brokerId: null, brokerName: null,
  effectiveDate: '2026-05-01', policyEndDate: '2027-02-01',
  remainingDays: 276,
  oldSumInsured: 3_500_000, newSumInsured: 4_500_000,
  oldNetPremium:    78_750, newNetPremium:    94_375,
  premiumAdjustment: 15_625,
  currencyCode: 'NGN',
  description: 'Vehicle value reassessed after bodywork upgrade.',
  notes: null,
  approvedBy: null, approvedAt: null,
  rejectedBy: null, rejectedAt: null,
  rejectionReason: null,
  createdAt: '2026-04-28T09:14:00Z',
  risks: [],
};

const statusVariant: Record<EndorsementDto['status'], 'active'|'pending'|'draft'|'rejected'> = {
  APPROVED: 'active', SUBMITTED: 'pending', DRAFT: 'draft', REJECTED: 'rejected',
};

function Row({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className="flex items-start gap-4 py-2.5" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-44 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className={`text-sm font-medium ${highlight ? 'text-primary' : 'text-foreground'}`}>{value}</p>
    </div>
  );
}

export default function EndorsementDetailPage() {
  const navigate = useNavigate();
  const { id }   = useParams<{ id: string }>();

  const endorsementQuery = useQuery<EndorsementDto>({
    queryKey: ['endorsements', id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: EndorsementDto }>(`/api/v1/endorsements/${id}`);
      return res.data.data;
    },
    enabled: !!id,
  });

  const e = endorsementQuery.data ?? mockEndorsement;

  if (endorsementQuery.isLoading && !endorsementQuery.data) {
    return (
      <div className="p-6 space-y-4 max-w-4xl">
        <Skeleton className="h-9 w-72" />
        <Skeleton className="h-32 w-full rounded-lg" />
        <Skeleton className="h-64 w-full rounded-lg" />
      </div>
    );
  }

  const endorsementTypeName = ENDORSEMENT_TYPE_LABELS[e.endorsementType];
  const canSubmit  = e.status === 'DRAFT';
  const canApprove = e.status === 'SUBMITTED';
  const isApproved = e.status === 'APPROVED';
  const isRejected = e.status === 'REJECTED';
  const isCredit   = e.premiumAdjustment < 0;
  const reasonText = e.description ?? e.notes ?? '—';

  return (
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader
        title={e.endorsementNumber}
        description={`${endorsementTypeName} · ${e.policyNumber} · ${e.customerName}`}
        breadcrumb={
          <button onClick={() => navigate('/endorsements')} className="text-sm text-muted-foreground hover:text-foreground">
            ← Endorsements
          </button>
        }
        actions={
          <div className="flex items-center gap-2 flex-wrap">
            <Badge variant={statusVariant[e.status]}>{e.status.toLowerCase()}</Badge>
            {canSubmit  && <Button size="sm">Submit for Approval</Button>}
            {canApprove && <Button size="sm" variant="outline">Reject</Button>}
            {canApprove && <Button size="sm">Approve Endorsement</Button>}
            {isApproved && <Button size="sm" variant="outline">Download Document</Button>}
          </div>
        }
      />

      <div className="grid gap-4 lg:grid-cols-2">
        {/* Endorsement details */}
        <Card>
          <CardHeader><CardTitle>Endorsement Details</CardTitle></CardHeader>
          <CardContent>
            <Row label="Policy"            value={e.policyNumber} />
            <Row label="Customer"          value={e.customerName} />
            <Row label="Product"           value={e.productName} />
            <Row label="Class of Business" value={e.classOfBusinessName} />
            <Row label="Type"              value={endorsementTypeName} />
            <Row label="Effective Date"    value={e.effectiveDate} />
            <Row label="Policy End Date"   value={e.policyEndDate} />
            <Row label="Reason"            value={reasonText} />
          </CardContent>
        </Card>

        {/* Premium impact */}
        <Card>
          <CardHeader>
            <CardTitle>Premium Impact</CardTitle>
          </CardHeader>
          <CardContent>
            <Row label="Original Sum Insured"  value={`₦${e.oldSumInsured.toLocaleString()}`} />
            <Row label="New Sum Insured"        value={`₦${e.newSumInsured.toLocaleString()}`} highlight />
            <Separator className="my-3" />
            <Row label="Original Net Premium"   value={`₦${e.oldNetPremium.toLocaleString()}`} />
            <Row label="New Net Premium"        value={`₦${e.newNetPremium.toLocaleString()}`} />
            <div className="flex items-center justify-between py-2.5">
              <p className="text-sm text-muted-foreground">Pro-rata Adjustment</p>
              <p className={`text-lg font-semibold ${isCredit ? 'text-destructive' : 'text-primary'}`}>
                {isCredit ? '−' : '+'}₦{Math.abs(e.premiumAdjustment).toLocaleString()}
              </p>
            </div>
            {isCredit
              ? <p className="text-xs text-muted-foreground">Credit note will be generated on approval.</p>
              : <p className="text-xs text-muted-foreground">Debit note will be generated on approval.</p>
            }
          </CardContent>
        </Card>
      </div>

      {/* Approval timeline */}
      <Card>
        <CardHeader><CardTitle>Approval Timeline</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {[
            { step: 'Created',  done: true,
              date: e.createdAt.slice(0, 10),
              user: undefined },
            { step: isApproved ? 'Approved'
                  : isRejected ? 'Rejected'
                  : canApprove ? 'Approval pending'
                  : 'Awaiting submission',
              done: isApproved || isRejected,
              date: isApproved && e.approvedAt ? e.approvedAt.slice(0, 10)
                  : isRejected && e.rejectedAt ? e.rejectedAt.slice(0, 10)
                  : undefined,
              user: isApproved ? e.approvedBy ?? undefined
                  : isRejected ? e.rejectedBy ?? undefined
                  : undefined },
          ].map((item, i) => (
            <div key={i} className="flex gap-4">
              <div className="flex flex-col items-center gap-1">
                <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold ${item.done ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'}`}>
                  {i + 1}
                </div>
                {i < 1 && <div className="w-px flex-1 bg-border min-h-[16px]" />}
              </div>
              <div className="pb-2 space-y-0.5">
                <p className="text-sm font-medium text-foreground">{item.step}</p>
                {item.date && (
                  <p className="text-xs text-muted-foreground">
                    {item.date}{item.user ? ` · ${item.user}` : ''}
                  </p>
                )}
              </div>
            </div>
          ))}
          {isRejected && e.rejectionReason && (
            <>
              <Separator className="my-2" />
              <div className="text-sm">
                <p className="text-xs text-muted-foreground mb-1">Rejection reason</p>
                <p className="text-foreground">{e.rejectionReason}</p>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
