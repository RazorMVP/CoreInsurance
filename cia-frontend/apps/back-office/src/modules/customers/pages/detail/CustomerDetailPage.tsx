import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle,
  EmptyState, PageHeader, Skeleton, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient, unwrapPageData, type SpringPageResponse } from '@cia/api-client';
import EditCustomerSheet from './EditCustomerSheet';

interface PolicyHistoryItem { id: string; policyNumber?: string | null; productName: string; status: string; netPremium: number; policyStartDate: string; policyEndDate: string; }
interface ClaimHistoryItem  { id: string; claimNumber: string; policyNumber: string; status: string; reserveAmount: number; incidentDate: string; }

type KycStatus    = 'VERIFIED' | 'PENDING' | 'FAILED' | 'RESUBMIT';
type CustomerStatus = 'ACTIVE' | 'INACTIVE' | 'BLACKLISTED';
type CustomerType = 'INDIVIDUAL' | 'CORPORATE';

interface CustomerDetail {
  id: string;
  customerNumber?: string;
  customerType: CustomerType;
  displayName: string;
  kycStatus: KycStatus;
  status?: CustomerStatus;
  customerStatus?: CustomerStatus;
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

const kycV: Record<string, 'active' | 'pending' | 'rejected'> = { VERIFIED: 'active', PENDING: 'pending', FAILED: 'rejected', RESUBMIT: 'pending' };
const stV:  Record<string, 'active' | 'draft'   | 'rejected'> = { ACTIVE: 'active', INACTIVE: 'draft', BLACKLISTED: 'rejected' };

function statusOf(customer: CustomerDetail): CustomerStatus {
  return customer.status ?? customer.customerStatus ?? 'ACTIVE';
}

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

  const customerQuery = useQuery<CustomerDetail>({
    queryKey: ['customers', id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CustomerDetail }>(`/api/v1/customers/${id}`);
      return res.data.data;
    },
    enabled: !!id,
  });

  const policiesQuery = useQuery<PolicyHistoryItem[]>({
    queryKey: ['customers', id, 'policies'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: SpringPageResponse<PolicyHistoryItem> | PolicyHistoryItem[] }>(
        '/api/v1/policies',
        { params: { customerId: id } },
      );
      return unwrapPageData(res.data.data);
    },
    enabled: !!id,
  });

  const claimsQuery = useQuery<ClaimHistoryItem[]>({
    queryKey: ['customers', id, 'claims'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: SpringPageResponse<ClaimHistoryItem> | ClaimHistoryItem[] }>(
        '/api/v1/claims',
        { params: { customerId: id } },
      );
      return unwrapPageData(res.data.data);
    },
    enabled: !!id,
  });

  const c = customerQuery.data;

  if (customerQuery.isLoading && !customerQuery.data) {
    return (
      <div className="p-6 space-y-4 max-w-4xl">
        <Skeleton className="h-9 w-72" />
        <Skeleton className="h-32 w-full rounded-lg" />
        <Skeleton className="h-64 w-full rounded-lg" />
      </div>
    );
  }

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

  const customerStatus = statusOf(c);
  const policies = policiesQuery.data ?? [];
  const claims   = claimsQuery.data ?? [];

  return (
    <>
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader
        title={c.displayName}
        description={`${c.customerType === 'INDIVIDUAL' ? 'Individual' : 'Corporate'} · ${c.customerNumber ?? c.id}`}
        breadcrumb={<button onClick={() => navigate('/customers')} className="text-sm text-muted-foreground hover:text-foreground">← Customers</button>}
        actions={
          <div className="flex gap-2">
            <Badge variant={kycV[c.kycStatus]}>{c.kycStatus.toLowerCase()}</Badge>
            <Badge variant={stV[customerStatus]}>{customerStatus.toLowerCase()}</Badge>
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
                        <td className="px-4 py-3 font-mono text-xs text-primary underline-offset-2 hover:underline">{p.policyNumber ?? 'Draft policy'}</td>
                        <td className="px-4 py-3 text-sm">{p.productName}</td>
                        <td className="px-4 py-3"><Badge variant={p.status === 'ACTIVE' ? 'active' : 'draft'} className="text-[10px]">{p.status.toLowerCase()}</Badge></td>
                        <td className="px-4 py-3 text-sm">₦{p.netPremium.toLocaleString()}</td>
                        <td className="px-4 py-3 text-xs text-muted-foreground">{p.policyStartDate} → {p.policyEndDate}</td>
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
                        <td className="px-4 py-3 text-sm">₦{cl.reserveAmount.toLocaleString()}</td>
                        <td className="px-4 py-3 text-xs text-muted-foreground">{cl.incidentDate}</td>
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
