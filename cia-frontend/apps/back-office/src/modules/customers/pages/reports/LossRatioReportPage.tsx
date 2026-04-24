import { Badge, Card, CardContent, CardHeader, CardTitle, PageHeader, StatCard } from '@cia/ui';

const mockData = [
  { class: 'Motor (Private)',   premiums: 12_400_000, claims: 3_200_000, lossRatio: 25.8 },
  { class: 'Fire & Burglary',   premiums: 8_200_000,  claims: 5_100_000, lossRatio: 62.2 },
  { class: 'Marine Cargo',      premiums: 4_100_000,  claims: 980_000,   lossRatio: 23.9 },
  { class: 'Motor (Commercial)',premiums: 6_700_000,  claims: 4_400_000, lossRatio: 65.7 },
];

export default function LossRatioReportPage() {
  const avgLossRatio = (mockData.reduce((s, r) => s + r.lossRatio, 0) / mockData.length).toFixed(1);
  return (
    <div className="p-6 space-y-5 max-w-4xl">
      <PageHeader title="Loss Ratio Report" description="Claims paid as a percentage of premiums earned, by class of business." />
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Total Premiums"   value={`₦${(31_400_000).toLocaleString()}`} />
        <StatCard label="Total Claims"     value={`₦${(13_680_000).toLocaleString()}`} />
        <StatCard label="Avg Loss Ratio"   value={`${avgLossRatio}%`} />
      </div>
      <Card>
        <CardHeader><CardTitle>By Class of Business</CardTitle></CardHeader>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead><tr className="border-b bg-muted/40">
              {['Class','Premiums (₦)','Claims (₦)','Loss Ratio','Rating'].map(h=>(
                <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
              ))}
            </tr></thead>
            <tbody>
              {mockData.map((r,i)=>(
                <tr key={r.class} className={i<mockData.length-1?'border-b':''}>
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
        </CardContent>
      </Card>
    </div>
  );
}
