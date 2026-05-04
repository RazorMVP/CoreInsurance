import { useNavigate } from 'react-router-dom';
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, PageHeader, Skeleton, StatCard } from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';

interface PeriodRow { period: string; endorsements: number; debits: number; credits: number; netPremium: number; }
interface TypeRow   { type: string; count: number; totalPremium: number; }

export default function DebitNoteAnalysisPage() {
  const navigate = useNavigate();

  const byPeriodQuery = useQuery<PeriodRow[]>({
    queryKey: ['endorsements', 'reports', 'debit-note-by-period'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: PeriodRow[] }>(
        '/api/v1/endorsements/reports/debit-note-by-period',
      );
      return res.data.data;
    },
  });
  const byTypeQuery = useQuery<TypeRow[]>({
    queryKey: ['endorsements', 'reports', 'debit-note-by-type'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: TypeRow[] }>(
        '/api/v1/endorsements/reports/debit-note-by-type',
      );
      return res.data.data;
    },
  });
  const byPeriod = byPeriodQuery.data ?? [];
  const byType   = byTypeQuery.data   ?? [];

  const totalEndorsements = byPeriod.reduce((s, r) => s + r.endorsements, 0);
  const totalDebits       = byPeriod.reduce((s, r) => s + r.debits, 0);
  const totalCredits      = byPeriod.reduce((s, r) => s + r.credits, 0);
  const totalNet          = byPeriod.reduce((s, r) => s + r.netPremium, 0);

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
        {byPeriodQuery.isLoading ? (
          <div className="p-4 space-y-2"><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>
        ) : (
          <table className="w-full text-sm">
            <thead><tr className="border-b bg-muted/40">
              {['Period','Endorsements','Debit Notes','Credit Notes','Net Premium'].map(h=>(
                <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>
              ))}
            </tr></thead>
            <tbody>
              {byPeriod.map((r,i)=>(
                <tr key={r.period} className={i<byPeriod.length-1?'border-b':''}>
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
        )}
        </CardContent>
      </Card>

      {/* By type */}
      <Card>
        <CardHeader><CardTitle>By Endorsement Type</CardTitle></CardHeader>
        <CardContent className="p-0">
        {byTypeQuery.isLoading ? (
          <div className="p-4 space-y-2"><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>
        ) : (
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
        )}
        </CardContent>
      </Card>
    </div>
  );
}
