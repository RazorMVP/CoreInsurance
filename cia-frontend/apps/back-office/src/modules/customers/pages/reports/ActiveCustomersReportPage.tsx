import { Card, CardContent, CardHeader, CardTitle, PageHeader, StatCard } from '@cia/ui';

const mockData = [
  { broker: 'Direct',             individual: 142, corporate: 38, total: 180 },
  { broker: 'Leadway Brokers',    individual: 67,  corporate: 22, total: 89  },
  { broker: 'Stanbic Brokers',    individual: 34,  corporate: 11, total: 45  },
  { broker: 'Other Brokers',      individual: 19,  corporate: 8,  total: 27  },
];

export default function ActiveCustomersReportPage() {
  const totalCustomers = mockData.reduce((s, r) => s + r.total, 0);
  const totalIndividual = mockData.reduce((s, r) => s + r.individual, 0);
  const totalCorporate  = mockData.reduce((s, r) => s + r.corporate,  0);
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
          <table className="w-full text-sm">
            <thead><tr className="border-b bg-muted/40">
              {['Channel','Individual','Corporate','Total','Share'].map(h=>(
                <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
              ))}
            </tr></thead>
            <tbody>
              {mockData.map((r,i)=>(
                <tr key={r.broker} className={i<mockData.length-1?'border-b':''}>
                  <td className="px-4 py-3 font-medium">{r.broker}</td>
                  <td className="px-4 py-3">{r.individual}</td>
                  <td className="px-4 py-3">{r.corporate}</td>
                  <td className="px-4 py-3 font-semibold">{r.total}</td>
                  <td className="px-4 py-3 text-muted-foreground">{((r.total/totalCustomers)*100).toFixed(1)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
