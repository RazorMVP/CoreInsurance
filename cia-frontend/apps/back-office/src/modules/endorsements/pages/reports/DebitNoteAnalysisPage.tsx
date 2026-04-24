import { useNavigate } from 'react-router-dom';
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader, StatCard } from '@cia/ui';

const mockData = [
  { period: 'Jan 2026', endorsements: 3, debits: 2, credits: 1, netPremium: 62_250 },
  { period: 'Feb 2026', endorsements: 7, debits: 5, credits: 2, netPremium: 134_500 },
  { period: 'Mar 2026', endorsements: 4, debits: 3, credits: 1, netPremium: 48_750 },
  { period: 'Apr 2026', endorsements: 6, debits: 4, credits: 2, netPremium: 91_000 },
  { period: 'May 2026', endorsements: 2, debits: 2, credits: 0, netPremium: 28_500 },
];

const byType = [
  { type: 'Renewal',             count: 6, totalPremium: 210_000 },
  { type: 'Extension of Period', count: 5, totalPremium: 87_500 },
  { type: 'Increase Sum Insured',count: 4, totalPremium: 95_250 },
  { type: 'Cancellation',        count: 3, totalPremium: -62_000 },
  { type: 'Decrease Sum Insured',count: 2, totalPremium: -28_750 },
  { type: 'Other',               count: 2, totalPremium: 12_500 },
];

export default function DebitNoteAnalysisPage() {
  const navigate = useNavigate();
  const totalEndorsements = mockData.reduce((s, r) => s + r.endorsements, 0);
  const totalDebits       = mockData.reduce((s, r) => s + r.debits, 0);
  const totalCredits      = mockData.reduce((s, r) => s + r.credits, 0);
  const totalNet          = mockData.reduce((s, r) => s + r.netPremium, 0);

  return (
    <div className="p-6 space-y-5 max-w-5xl">
      <PageHeader
        title="Debit Note Analysis"
        description="Endorsement premium movements by period and type."
        breadcrumb={<button onClick={() => navigate('/endorsements')} className="text-sm text-muted-foreground hover:text-foreground">← Endorsements</button>}
        actions={<Button variant="outline" size="sm">Export CSV</Button>}
      />

      {/* Summary stats */}
      <div className="grid grid-cols-4 gap-4">
        <StatCard label="Total Endorsements" value={String(totalEndorsements)} />
        <StatCard label="Debit Notes"        value={String(totalDebits)} />
        <StatCard label="Credit Notes"       value={String(totalCredits)} />
        <StatCard label="Net Premium"        value={`₦${totalNet.toLocaleString()}`} />
      </div>

      {/* By period */}
      <Card>
        <CardHeader><CardTitle>By Period</CardTitle></CardHeader>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead><tr className="border-b bg-muted/40">
              {['Period','Endorsements','Debit Notes','Credit Notes','Net Premium'].map(h=>(
                <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
              ))}
            </tr></thead>
            <tbody>
              {mockData.map((r,i)=>(
                <tr key={r.period} className={i<mockData.length-1?'border-b':''}>
                  <td className="px-4 py-3 font-medium">{r.period}</td>
                  <td className="px-4 py-3">{r.endorsements}</td>
                  <td className="px-4 py-3">{r.debits}</td>
                  <td className="px-4 py-3">{r.credits}</td>
                  <td className={`px-4 py-3 font-medium tabular-nums ${r.netPremium<0?'text-destructive':''}`}>
                    {r.netPremium<0?'−':''}₦{Math.abs(r.netPremium).toLocaleString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {/* By type */}
      <Card>
        <CardHeader><CardTitle>By Endorsement Type</CardTitle></CardHeader>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead><tr className="border-b bg-muted/40">
              {['Type','Count','Total Premium'].map(h=>(
                <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
              ))}
            </tr></thead>
            <tbody>
              {byType.map((r,i)=>(
                <tr key={r.type} className={i<byType.length-1?'border-b':''}>
                  <td className="px-4 py-3">
                    <Badge variant="outline" className="text-xs">{r.type}</Badge>
                  </td>
                  <td className="px-4 py-3">{r.count}</td>
                  <td className={`px-4 py-3 font-medium tabular-nums ${r.totalPremium<0?'text-destructive':''}`}>
                    {r.totalPremium<0?'−':''}₦{Math.abs(r.totalPremium).toLocaleString()}
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
