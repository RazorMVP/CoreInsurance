import { Card, CardContent, CardHeader, CardTitle, PageHeader, Skeleton, StatCard } from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';

interface ActiveCustomersRow { broker: string; individual: number; corporate: number; total: number; }

export default function ActiveCustomersReportPage() {
  const reportQuery = useQuery<ActiveCustomersRow[]>({
    queryKey: ['customers', 'reports', 'active-by-channel'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ActiveCustomersRow[] }>(
        '/api/v1/customers/reports/active-by-channel',
      );
      return res.data.data;
    },
  });
  const data = reportQuery.data ?? [];
  const totalCustomers = data.reduce((s, r) => s + r.total, 0);
  const totalIndividual = data.reduce((s, r) => s + r.individual, 0);
  const totalCorporate  = data.reduce((s, r) => s + r.corporate,  0);
  return (
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader title="Active Customers Report" description="Active customer count by onboarding channel and type." />
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Total Active" value={String(totalCustomers)} />
        <StatCard label="Individual"   value={String(totalIndividual)} />
        <StatCard label="Corporate"    value={String(totalCorporate)} />
      </div>
      <Card>
        <CardHeader><CardTitle>By Onboarding Channel</CardTitle></CardHeader>
        <CardContent className="p-0">
        {reportQuery.isLoading ? (
          <div className="p-4 space-y-2">
            <Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" />
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead><tr className="border-b bg-muted/40">
              {['Channel','Individual','Corporate','Total','Share'].map(h=>(
                <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
              ))}
            </tr></thead>
            <tbody>
              {data.map((r,i)=>(
                <tr key={r.broker} className={i<data.length-1?'border-b':''}>
                  <td className="px-4 py-3 font-medium">{r.broker}</td>
                  <td className="px-4 py-3">{r.individual}</td>
                  <td className="px-4 py-3">{r.corporate}</td>
                  <td className="px-4 py-3 font-semibold">{r.total}</td>
                  <td className="px-4 py-3 text-muted-foreground">{((r.total/totalCustomers)*100).toFixed(1)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        </CardContent>
      </Card>
    </div>
  );
}
