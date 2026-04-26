import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle,
  EmptyState, PageHeader, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import EditCustomerSheet from './EditCustomerSheet';

type KycStatus    = 'VERIFIED' | 'PENDING' | 'FAILED' | 'RESUBMIT';
type CustomerStatus = 'ACTIVE' | 'INACTIVE' | 'BLACKLISTED';
type CustomerType = 'INDIVIDUAL' | 'CORPORATE';

interface MockCustomer {
  id: string;
  customerNumber: string;
  customerType: CustomerType;
  displayName: string;
  kycStatus: KycStatus;
  status: CustomerStatus;
  // contact
  email: string;
  phone: string;
  address: string;
  createdAt: string;
  brokerName?: string;
  // individual
  dateOfBirth?: string;
  idType?: string;
  idNumber?: string;
  idExpiryDate?: string;
  occupation?: string;
  // corporate
  companyName?: string;
  rcNumber?: string;
  industry?: string;
  contactPerson?: string;
  directorName?: string;
  directors?: { id: string; firstName: string; lastName: string; dateOfBirth?: string; idType?: string; idNumber?: string; idExpiryDate?: string }[];
}

const MOCK_CUSTOMERS: MockCustomer[] = [
  {
    id: 'c1', customerNumber: 'CUST/2026/IND/00000001', customerType: 'INDIVIDUAL',
    displayName: 'Chioma Okafor', kycStatus: 'VERIFIED', status: 'ACTIVE',
    email: 'chioma@email.ng', phone: '+234 803 111 0001',
    address: '14 Adeola Odeku Street, Victoria Island, Lagos', createdAt: '2026-01-15',
    dateOfBirth: '1990-05-12', idType: 'NIN', idNumber: '12345678901', occupation: 'Software Engineer',
  },
  {
    id: 'c2', customerNumber: 'CUST/2026/CORP/00000001', customerType: 'CORPORATE',
    displayName: 'Alaba Trading Co.', kycStatus: 'VERIFIED', status: 'ACTIVE',
    email: 'info@alaba.ng', phone: '+234 701 222 0002',
    address: '7 Commerce Road, Apapa, Lagos', createdAt: '2026-01-20',
    brokerName: 'Leadway Brokers',
    companyName: 'Alaba Trading Co.', rcNumber: 'RC-123456', industry: 'Trading',
    contactPerson: 'Tunde Alaba', directorName: 'Tunde Alaba / Bisi Alaba',
    directors: [
      { id: 'd1', firstName: 'Tunde', lastName: 'Alaba', dateOfBirth: '1975-04-10', idType: 'NIN',      idNumber: '22334455667' },
      { id: 'd2', firstName: 'Bisi',  lastName: 'Alaba', dateOfBirth: '1978-09-22', idType: 'PASSPORT', idNumber: 'B98765432', idExpiryDate: '2030-06-15' },
    ],
  },
  {
    id: 'c3', customerNumber: 'CUST/2026/IND/00000002', customerType: 'INDIVIDUAL',
    displayName: 'Emeka Eze', kycStatus: 'PENDING', status: 'ACTIVE',
    email: 'emeka@email.ng', phone: '+234 805 333 0003',
    address: '22 Enugu Road, GRA, Enugu', createdAt: '2026-02-01',
    dateOfBirth: '1985-11-20', idType: 'PASSPORT', idNumber: 'A12345678', idExpiryDate: '2029-03-15', occupation: 'Civil Servant',
  },
  {
    id: 'c4', customerNumber: 'CUST/2026/CORP/00000002', customerType: 'CORPORATE',
    displayName: 'Danforth Logistics', kycStatus: 'FAILED', status: 'ACTIVE',
    email: 'ops@danforth.ng', phone: '+234 809 444 0004',
    address: '5 Industrial Layout, Trans-Amadi, Port Harcourt', createdAt: '2026-02-10',
    companyName: 'Danforth Logistics Ltd', rcNumber: 'RC-789012', industry: 'Logistics',
    contactPerson: 'David Danforth', directorName: 'David Danforth',
    directors: [
      { id: 'd3', firstName: 'David',   lastName: 'Danforth', dateOfBirth: '1980-01-15', idType: 'DRIVERS_LICENSE', idNumber: 'DL-NG-0055123', idExpiryDate: '2028-03-20' },
      { id: 'd4', firstName: 'Chidera', lastName: 'Obi',      dateOfBirth: '1983-07-30', idType: 'NIN',             idNumber: '33445566778' },
    ],
  },
  {
    id: 'c5', customerNumber: 'CUST/2026/IND/00000003', customerType: 'INDIVIDUAL',
    displayName: 'Ngozi Adeyemi', kycStatus: 'VERIFIED', status: 'INACTIVE',
    email: 'ngozi@email.ng', phone: '+234 706 555 0005',
    address: '8 Awolowo Avenue, Bodija, Ibadan', createdAt: '2026-02-15',
    brokerName: 'Stanbic Brokers',
    dateOfBirth: '1992-03-08', idType: 'DRIVERS_LICENSE', idNumber: 'DL-NG-0099871', idExpiryDate: '2027-11-30', occupation: 'Pharmacist',
  },
];

const mockPoliciesByCustomer: Record<string, { id: string; policyNumber: string; product: string; status: string; premium: number; startDate: string; endDate: string }[]> = {
  c1: [
    { id: 'p1', policyNumber: 'POL-2026-00012', product: 'Private Motor Comprehensive', status: 'ACTIVE',  premium: 285000, startDate: '2026-02-01', endDate: '2027-02-01' },
    { id: 'p2', policyNumber: 'POL-2025-00088', product: 'Fire & Burglary Standard',    status: 'EXPIRED', premium: 120000, startDate: '2025-03-01', endDate: '2026-03-01' },
  ],
  c2: [
    { id: 'p3', policyNumber: 'POL-2026-00031', product: 'Commercial Property', status: 'ACTIVE', premium: 750000, startDate: '2026-01-15', endDate: '2027-01-15' },
  ],
  c3: [], c4: [], c5: [],
};

const mockClaimsByCustomer: Record<string, { id: string; claimNumber: string; policyNumber: string; status: string; amount: number; date: string }[]> = {
  c1: [{ id: 'cl1', claimNumber: 'CLM-2026-00003', policyNumber: 'POL-2026-00012', status: 'PROCESSING', amount: 450000, date: '2026-03-10' }],
  c2: [], c3: [], c4: [], c5: [],
};

const kycV: Record<string, 'active' | 'pending' | 'rejected'> = { VERIFIED: 'active', PENDING: 'pending', FAILED: 'rejected', RESUBMIT: 'pending' };
const stV:  Record<string, 'active' | 'draft'   | 'rejected'> = { ACTIVE: 'active', INACTIVE: 'draft', BLACKLISTED: 'rejected' };

function Row({ label, value }: { label: string; value?: string }) {
  return (
    <div className="flex items-start gap-4 py-2.5" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-40 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value ?? '—'}</p>
    </div>
  );
}

export default function CustomerDetailPage() {
  const navigate    = useNavigate();
  const { id }      = useParams<{ id: string }>();
  const [editOpen, setEditOpen] = useState(false);
  const c           = MOCK_CUSTOMERS.find(x => x.id === id);

  if (!c) {
    return (
      <div className="p-6">
        <EmptyState
          title="Customer not found"
          description="This customer record doesn't exist or has been removed."
          action={<Button onClick={() => navigate('/customers')}>← Back to Customers</Button>}
        />
      </div>
    );
  }

  const policies = mockPoliciesByCustomer[c.id] ?? [];
  const claims   = mockClaimsByCustomer[c.id]   ?? [];

  return (
    <>
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader
        title={c.displayName}
        description={`${c.customerType === 'INDIVIDUAL' ? 'Individual' : 'Corporate'} · ${c.customerNumber}`}
        breadcrumb={<button onClick={() => navigate('/customers')} className="text-sm text-muted-foreground hover:text-foreground">← Customers</button>}
        actions={
          <div className="flex gap-2">
            <Badge variant={kycV[c.kycStatus]}>{c.kycStatus.toLowerCase()}</Badge>
            <Badge variant={stV[c.status]}>{c.status.toLowerCase()}</Badge>
            <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>Edit Customer</Button>
            <Button size="sm">New Policy</Button>
          </div>
        }
      />

      <Tabs defaultValue="summary">
        <TabsList>
          <TabsTrigger value="summary">Summary</TabsTrigger>
          <TabsTrigger value="kyc">KYC</TabsTrigger>
          <TabsTrigger value="policies">
            Policies <span className="ml-1.5 rounded-full bg-muted px-1.5 py-0.5 text-[10px]">{policies.length}</span>
          </TabsTrigger>
          <TabsTrigger value="claims">
            Claims <span className="ml-1.5 rounded-full bg-muted px-1.5 py-0.5 text-[10px]">{claims.length}</span>
          </TabsTrigger>
        </TabsList>

        {/* Summary */}
        <TabsContent value="summary" className="mt-4">
          <Card>
            <CardHeader><CardTitle>Contact Details</CardTitle></CardHeader>
            <CardContent>
              <Row label="Customer ID"   value={c.customerNumber} />
              <Row label="Customer Type" value={c.customerType === 'INDIVIDUAL' ? 'Individual' : 'Corporate'} />
              <Row label="Email"         value={c.email} />
              <Row label="Phone"         value={c.phone} />
              <Row label="Address"       value={c.address} />
              {c.customerType === 'INDIVIDUAL' ? (
                <>
                  <Row label="Date of Birth" value={c.dateOfBirth} />
                  <Row label="Occupation"    value={c.occupation} />
                </>
              ) : (
                <>
                  <Row label="RC Number"      value={c.rcNumber} />
                  <Row label="Industry"       value={c.industry} />
                  <Row label="Contact Person" value={c.contactPerson} />
                  <Row label="Directors"      value={c.directorName} />
                </>
              )}
              <Row label="Channel" value={c.brokerName ?? 'Direct'} />
              <Row label="Created" value={c.createdAt} />
            </CardContent>
          </Card>
        </TabsContent>

        {/* KYC */}
        <TabsContent value="kyc" className="mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>KYC Verification</CardTitle>
                <Badge variant={kycV[c.kycStatus]}>{c.kycStatus.toLowerCase()}</Badge>
              </div>
            </CardHeader>
            <CardContent>
              {c.customerType === 'INDIVIDUAL' ? (
                <>
                  <Row label="ID Type"   value={c.idType?.replace(/_/g, ' ')} />
                  <Row label="ID Number" value={c.idNumber} />
                  {c.idExpiryDate && <Row label="ID Expiry Date" value={c.idExpiryDate} />}
                </>
              ) : (
                <>
                  <Row label="RC Number"    value={c.rcNumber} />
                  <Row label="Company Name" value={c.companyName} />
                  <Row label="Directors"    value={c.directorName} />
                  {c.idNumber && <Row label="Director ID" value={c.idNumber} />}
                </>
              )}
              <div className="mt-4">
                <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>
                  Edit Customer / Update KYC
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* Policies */}
        <TabsContent value="policies" className="mt-4">
          <Card>
            <CardContent className="p-0">
              {policies.length === 0 ? (
                <p className="p-6 text-sm text-muted-foreground">No policies on record for this customer.</p>
              ) : (
                <table className="w-full text-sm">
                  <thead><tr className="border-b bg-muted/40">
                    {['Policy No.', 'Product', 'Status', 'Premium (₦)', 'Period'].map(h => (
                      <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
                    ))}
                  </tr></thead>
                  <tbody>
                    {policies.map((p, i) => (
                      <tr
                        key={p.id}
                        className={`cursor-pointer hover:bg-muted/40 transition-colors ${i < policies.length - 1 ? 'border-b' : ''}`}
                        onClick={() => navigate(`/policies/${p.id}`)}
                      >
                        <td className="px-4 py-3 font-mono text-xs text-primary underline-offset-2 hover:underline">{p.policyNumber}</td>
                        <td className="px-4 py-3 text-sm">{p.product}</td>
                        <td className="px-4 py-3"><Badge variant={p.status === 'ACTIVE' ? 'active' : 'draft'} className="text-[10px]">{p.status.toLowerCase()}</Badge></td>
                        <td className="px-4 py-3 text-sm">₦{p.premium.toLocaleString()}</td>
                        <td className="px-4 py-3 text-xs text-muted-foreground">{p.startDate} → {p.endDate}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Claims */}
        <TabsContent value="claims" className="mt-4">
          <Card>
            <CardContent className="p-0">
              {claims.length === 0 ? (
                <p className="p-6 text-sm text-muted-foreground">No claims on record for this customer.</p>
              ) : (
                <table className="w-full text-sm">
                  <thead><tr className="border-b bg-muted/40">
                    {['Claim No.', 'Policy', 'Status', 'Amount (₦)', 'Date'].map(h => (
                      <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
                    ))}
                  </tr></thead>
                  <tbody>
                    {claims.map((cl, i) => (
                      <tr
                        key={cl.id}
                        className={`cursor-pointer hover:bg-muted/40 transition-colors ${i < claims.length - 1 ? 'border-b' : ''}`}
                        onClick={() => navigate(`/claims/${cl.id}`)}
                      >
                        <td className="px-4 py-3 font-mono text-xs text-primary underline-offset-2 hover:underline">{cl.claimNumber}</td>
                        <td className="px-4 py-3 font-mono text-xs">{cl.policyNumber}</td>
                        <td className="px-4 py-3"><Badge variant="pending" className="text-[10px]">{cl.status.toLowerCase()}</Badge></td>
                        <td className="px-4 py-3 text-sm">₦{cl.amount.toLocaleString()}</td>
                        <td className="px-4 py-3 text-xs text-muted-foreground">{cl.date}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>

    <EditCustomerSheet
      open={editOpen}
      onOpenChange={setEditOpen}
      customer={{
        id:            c.id,
        customerType:  c.customerType,
        email:         c.email,
        phone:         c.phone,
        address:       c.address,
        contactPerson: c.contactPerson,
        brokerName:    c.brokerName,
        brokerId:      undefined,
        idType:        c.idType,
        idNumber:      c.idNumber,
        idExpiryDate:  c.idExpiryDate,
        directors:     c.directors,
      }}
      onSuccess={() => setEditOpen(false)}
    />
    </>
  );
}
