import { useNavigate, useParams } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader, Separator, Skeleton,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type EndorsementDto } from '@cia/api-client';

type MockEndorsement = Omit<EndorsementDto, 'updatedAt'> & {
  updatedAt: string;
  policyCustomer: string;
  endorsementTypeName: string;
  originalSumInsured: number;
  newSumInsured: number;
  originalPremium: number;
  proRataPremium: number;
  debitNoteNumber?: string;
  reason: string;
};

// allow-mock: fallback while useQuery is in flight or for unknown ids
const mockEndorsement: MockEndorsement = {
  id: 'end2', endorsementNumber: 'END-2026-00002',
  policyId: 'pol1', policyNumber: 'POL-2026-00001',
  policyCustomer: 'Chioma Okafor',
  endorsementTypeName: 'Increase Sum Insured',
  status: 'SUBMITTED',
  endorsementType: 'INCREASE_SI',
  originalSumInsured: 3_500_000, newSumInsured: 4_500_000,
  originalPremium: 78_750, proRataPremium: 15_625,
  sumInsured: 4_500_000, premium: 15_625,
  startDate: '2026-05-01', endDate: '2027-02-01',
  debitNoteNumber: undefined,
  reason: 'Vehicle value reassessed after bodywork upgrade.',
  createdAt: '2026-04-28', updatedAt: '2026-04-29',
};

const statusVariant: Record<string, 'active'|'pending'|'draft'|'rejected'> = {
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

  const endorsementQuery = useQuery<MockEndorsement>({
    queryKey: ['endorsements', id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: MockEndorsement }>(`/api/v1/endorsements/${id}`);
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

  const canSubmit  = e.status === 'DRAFT';
  const canApprove = e.status === 'SUBMITTED';
  const isApproved = e.status === 'APPROVED';

  const premiumChange = e.proRataPremium;
  const isCredit = premiumChange < 0;

  return (
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader
        title={e.endorsementNumber}
        description={`${e.endorsementTypeName} · ${e.policyNumber} · ${e.policyCustomer}`}
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
            {isApproved && e.debitNoteNumber && <Button size="sm" variant="outline">Download Document</Button>}
          </div>
        }
      />

      <div className="grid gap-4 lg:grid-cols-2">
        {/* Endorsement details */}
        <Card>
          <CardHeader><CardTitle>Endorsement Details</CardTitle></CardHeader>
          <CardContent>
            <Row label="Policy"            value={e.policyNumber} />
            <Row label="Customer"          value={e.policyCustomer} />
            <Row label="Type"              value={e.endorsementTypeName} />
            <Row label="Effective Date"    value={e.startDate} />
            <Row label="End Date"          value={e.endDate} />
            <Row label="Reason"            value={e.reason} />
          </CardContent>
        </Card>

        {/* Premium impact */}
        <Card>
          <CardHeader>
            <CardTitle>Premium Impact</CardTitle>
          </CardHeader>
          <CardContent>
            <Row label="Original Sum Insured"  value={`₦${e.originalSumInsured.toLocaleString()}`} />
            <Row label="New Sum Insured"        value={`₦${e.newSumInsured.toLocaleString()}`} highlight />
            <Separator className="my-3" />
            <Row label="Original Annual Premium" value={`₦${e.originalPremium.toLocaleString()}`} />
            <div className="flex items-center justify-between py-2.5">
              <p className="text-sm text-muted-foreground">Pro-rata Premium</p>
              <p className={`text-lg font-semibold ${isCredit ? 'text-destructive' : 'text-primary'}`}>
                {isCredit ? '−' : '+'}₦{Math.abs(premiumChange).toLocaleString()}
              </p>
            </div>
            {isCredit
              ? <p className="text-xs text-muted-foreground">Credit note will be generated on approval.</p>
              : <p className="text-xs text-muted-foreground">Debit note will be generated on approval.</p>
            }
            {e.debitNoteNumber && (
              <>
                <Separator className="my-3" />
                <Row label="Debit Note" value={e.debitNoteNumber} />
              </>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Approval timeline */}
      <Card>
        <CardHeader><CardTitle>Approval Timeline</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {[
            { step: 'Created',           done: true,  date: e.createdAt,    user: 'Chidi Okafor' },
            { step: 'Submitted',         done: true,  date: e.updatedAt,    user: 'Chidi Okafor' },
            { step: 'Approval pending',  done: false, date: undefined,      user: undefined },
          ].map((item, i) => (
            <div key={i} className="flex gap-4">
              <div className="flex flex-col items-center gap-1">
                <div className={`h-7 w-7 rounded-full flex items-center justify-center text-xs font-bold ${item.done ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'}`}>
                  {i + 1}
                </div>
                {i < 2 && <div className="w-px flex-1 bg-border min-h-[16px]" />}
              </div>
              <div className="pb-2 space-y-0.5">
                <p className="text-sm font-medium text-foreground">{item.step}</p>
                {item.date && item.user && (
                  <p className="text-xs text-muted-foreground">{item.date} · {item.user}</p>
                )}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
