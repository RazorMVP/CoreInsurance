import { Badge, Card, CardContent, CardHeader, CardTitle, PageHeader, Skeleton, StatCard } from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';

interface LossRatioRow { class: string; premiums: number; claims: number; lossRatio: number; }

export default function LossRatioReportPage() {
  const reportQuery = useQuery<LossRatioRow[]>({
    queryKey: ['customers', 'reports', 'loss-ratio-by-class'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: LossRatioRow[] }>(
        '/api/v1/customers/reports/loss-ratio-by-class',
      );
      return res.data.data;
    },
  });
  const data = reportQuery.data ?? [];
  const avgLossRatio = data.length ? (data.reduce((s, r) => s + r.lossRatio, 0) / data.length).toFixed(1) : '0.0';
  const totalPremiums = data.reduce((s, r) => s + r.premiums, 0);
  const totalClaims   = data.reduce((s, r) => s + r.claims,   0);
  return (
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader title="Loss Ratio Report" description="Claims paid as a percentage of premiums earned, by class of business." />
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Total Premiums"   value={`₦${totalPremiums.toLocaleString()}`} />
        <StatCard label="Total Claims"     value={`₦${totalClaims.toLocaleString()}`} />
        <StatCard label="Avg Loss Ratio"   value={`${avgLossRatio}%`} />
      </div>
      <Card>
        <CardHeader><CardTitle>By Class of Business</CardTitle></CardHeader>
        <CardContent className="p-0">
        {reportQuery.isLoading ? (
          <div className="p-4 space-y-2"><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>
        ) : (
          <table className="w-full text-sm">
            <thead><tr className="border-b bg-muted/40">
              {['Class','Premiums (₦)','Claims (₦)','Loss Ratio','Rating'].map(h=>(
                <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
              ))}
            </tr></thead>
            <tbody>
              {data.map((r,i)=>(
                <tr key={r.class} className={i<data.length-1?'border-b':''}>
                  <td className="px-4 py-3 font-medium">{r.class}</td>
                  <td className="px-4 py-3">₦{r.premiums.toLocaleString()}</td>
                  <td className="px-4 py-3">₦{r.claims.toLocaleString()}</td>
                  <td className="px-4 py-3 font-semibold">{r.lossRatio}%</td>
                  <td className="px-4 py-3">
                    <Badge variant={r.lossRatio<40?'active':r.lossRatio<65?'pending':'rejected'} className="text-[10px]">
                      {r.lossRatio<40?'Good':r.lossRatio<65?'Moderate':'High'}
                    </Badge>
                  </td>
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
