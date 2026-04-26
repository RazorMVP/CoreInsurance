import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle,
  PageHeader, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';

const mockCustomer = {
  id: 'c1', customerNumber: 'CUST/2026/IND/00000001',
  customerType: 'INDIVIDUAL' as const, displayName: 'Chioma Okafor',
  email: 'chioma@email.ng', phone: '+234 803 111 0001',
  kycStatus: 'VERIFIED' as const, status: 'ACTIVE' as const,
  brokerId: undefined, brokerName: undefined, createdAt: '2026-01-15',
  dateOfBirth: '1990-05-12', idType: 'NIN', idNumber: '12345678901',
  address: '14 Adeola Odeku Street, Victoria Island, Lagos', occupation: 'Software Engineer',
};

const mockPolicies = [
  { id: 'p1', policyNumber: 'POL-2026-00012', product: 'Private Motor Comprehensive', status: 'ACTIVE',  premium: 285000, startDate: '2026-02-01', endDate: '2027-02-01' },
  { id: 'p2', policyNumber: 'POL-2025-00088', product: 'Fire & Burglary Standard',    status: 'EXPIRED', premium: 120000, startDate: '2025-03-01', endDate: '2026-03-01' },
];

const mockClaims = [
  { id: 'cl1', claimNumber: 'CLM-2026-00003', policyNumber: 'POL-2026-00012', status: 'PROCESSING', amount: 450000, date: '2026-03-10' },
];

const kycV: Record<string, 'active'|'pending'|'rejected'> = { VERIFIED:'active', PENDING:'pending', FAILED:'rejected', RESUBMIT:'pending' };
const stV:  Record<string, 'active'|'draft'|'rejected'>   = { ACTIVE:'active', INACTIVE:'draft', BLACKLISTED:'rejected' };

function Row({ label, value }: { label: string; value?: string }) {
  return (
    <div className="flex items-start gap-4 py-2.5" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value ?? '—'}</p>
    </div>
  );
}

export default function CustomerDetailPage() {
  const navigate = useNavigate();
  const c = mockCustomer;

  return (
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader
        title={c.displayName}
        description={`${c.customerType === 'INDIVIDUAL' ? 'Individual' : 'Corporate'} · ${c.customerNumber}`}
        breadcrumb={<button onClick={() => navigate('/customers')} className="text-sm text-muted-foreground hover:text-foreground">← Customers</button>}
        actions={
          <div className="flex gap-2">
            <Badge variant={kycV[c.kycStatus]}>{c.kycStatus.toLowerCase()}</Badge>
            <Badge variant={stV[c.status]}>{c.status.toLowerCase()}</Badge>
            <Button variant="outline" size="sm">Update KYC</Button>
            <Button size="sm">New Policy</Button>
          </div>
        }
      />

      <Tabs defaultValue="summary">
        <TabsList>
          <TabsTrigger value="summary">Summary</TabsTrigger>
          <TabsTrigger value="kyc">KYC</TabsTrigger>
          <TabsTrigger value="policies">Policies <span className="ml-1.5 rounded-full bg-muted px-1.5 py-0.5 text-[10px]">{mockPolicies.length}</span></TabsTrigger>
          <TabsTrigger value="claims">Claims <span className="ml-1.5 rounded-full bg-muted px-1.5 py-0.5 text-[10px]">{mockClaims.length}</span></TabsTrigger>
        </TabsList>

        <TabsContent value="summary" className="mt-4">
          <Card>
            <CardHeader><CardTitle>Contact Details</CardTitle></CardHeader>
            <CardContent>
              <Row label="Customer ID"   value={c.customerNumber} />
              <Row label="Email"         value={c.email} />
              <Row label="Phone"         value={c.phone} />
              <Row label="Address"       value={c.address} />
              <Row label="Date of Birth" value={c.dateOfBirth} />
              <Row label="Occupation"    value={c.occupation} />
              <Row label="Broker"        value={c.brokerName ?? 'Direct'} />
              <Row label="Created"       value={c.createdAt} />
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="kyc" className="mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>KYC Verification</CardTitle>
                <Badge variant={kycV[c.kycStatus]}>{c.kycStatus.toLowerCase()}</Badge>
              </div>
            </CardHeader>
            <CardContent>
              <Row label="ID Type"  value={c.idType.replace('_', ' ')} />
              <Row label="ID Number" value={c.idNumber} />
              <div className="mt-4"><Button variant="outline" size="sm">Re-submit KYC</Button></div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="policies" className="mt-4">
          <Card>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <thead><tr className="border-b bg-muted/40">
                  {['Policy No.','Product','Status','Premium (₦)','Period'].map(h=>(
                    <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
                  ))}
                </tr></thead>
                <tbody>
                  {mockPolicies.map((p,i)=>(
                    <tr key={p.id} className={i<mockPolicies.length-1?'border-b':''}>
                      <td className="px-4 py-3 font-mono text-xs text-primary">{p.policyNumber}</td>
                      <td className="px-4 py-3 text-sm">{p.product}</td>
                      <td className="px-4 py-3"><Badge variant={p.status==='ACTIVE'?'active':'draft'} className="text-[10px]">{p.status.toLowerCase()}</Badge></td>
                      <td className="px-4 py-3 text-sm">₦{p.premium.toLocaleString()}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{p.startDate} → {p.endDate}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="claims" className="mt-4">
          <Card>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <thead><tr className="border-b bg-muted/40">
                  {['Claim No.','Policy','Status','Amount (₦)','Date'].map(h=>(
                    <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
                  ))}
                </tr></thead>
                <tbody>
                  {mockClaims.map((c,i)=>(
                    <tr key={c.id} className={i<mockClaims.length-1?'border-b':''}>
                      <td className="px-4 py-3 font-mono text-xs text-primary">{c.claimNumber}</td>
                      <td className="px-4 py-3 font-mono text-xs">{c.policyNumber}</td>
                      <td className="px-4 py-3"><Badge variant="pending" className="text-[10px]">{c.status.toLowerCase()}</Badge></td>
                      <td className="px-4 py-3 text-sm">₦{c.amount.toLocaleString()}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{c.date}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
