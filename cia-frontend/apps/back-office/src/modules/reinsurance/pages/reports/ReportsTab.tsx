import { Badge, Button, Card, CardContent, PageSection, Separator, StatCard, Tabs, TabsContent, TabsList, TabsTrigger } from '@cia/ui';

// Premium bordereaux
const premBordereaux = [
  { treaty: 'Motor Surplus 2026',  month: 'Mar 2026', policies: 3, grossPremium: 185_250, cedingPremium: 75_000, commission: 15_000 },
  { treaty: 'Fire QS 2026',         month: 'Mar 2026', policies: 2, grossPremium: 195_000, cedingPremium: 78_000, commission: 19_500 },
  { treaty: 'Marine XOL 2026',      month: 'Mar 2026', policies: 1, grossPremium: 60_000,  cedingPremium: 36_000, commission: 7_200 },
];

// Claims bordereaux
const claimsBordereaux = [
  { treaty: 'Motor Surplus 2026', month: 'Mar 2026', claims: 1, grossLoss: 850_000, cededLoss: 270_000, recovery: 0 },
  { treaty: 'Fire QS 2026',       month: 'Mar 2026', claims: 1, grossLoss: 450_000, cededLoss: 270_000, recovery: 0 },
];

// RI Recoveries
const recoveries = [
  { id: 'rec1', claimNumber: 'CLM-2026-00004', treaty: 'Fire QS 2026', grossPaid: 265_000, riShare: 60, riRecovery: 159_000, status: 'RECOVERED' },
  { id: 'rec2', claimNumber: 'CLM-2026-00001', treaty: 'Motor Surplus 2026', grossPaid: 0, riShare: 43, riRecovery: 0, status: 'PENDING' },
];

const totalCedingPremium = premBordereaux.reduce((s, r) => s + r.cedingPremium, 0);
const totalCededLoss     = claimsBordereaux.reduce((s, r) => s + r.cededLoss, 0);
const totalRecovered     = recoveries.filter(r => r.status === 'RECOVERED').reduce((s, r) => s + r.riRecovery, 0);

export default function ReportsTab() {
  return (
    <Tabs defaultValue="bordereaux">
      <TabsList>
        <TabsTrigger value="bordereaux">Bordereaux</TabsTrigger>
        <TabsTrigger value="recoveries">Recoveries</TabsTrigger>
        <TabsTrigger value="returns">Returns</TabsTrigger>
      </TabsList>

      {/* Bordereaux */}
      <TabsContent value="bordereaux" className="mt-4 space-y-5">
        <div className="grid grid-cols-3 gap-4">
          <StatCard label="Ceding Premium (MTD)"  value={`₦${totalCedingPremium.toLocaleString()}`} />
          <StatCard label="Ceded Losses (MTD)"     value={`₦${totalCededLoss.toLocaleString()}`} />
          <StatCard label="RI Recoveries (YTD)"    value={`₦${totalRecovered.toLocaleString()}`} />
        </div>

        <PageSection title="Premium Bordereaux" description="Monthly ceding premium summary by treaty." actions={<Button variant="outline" size="sm">Export</Button>}>
          <Card>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <thead><tr className="border-b bg-muted/40">{['Treaty','Month','Policies','Gross Premium','Ceding Premium','Commission'].map(h => <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>)}</tr></thead>
                <tbody>
                  {premBordereaux.map((r, i) => (
                    <tr key={r.treaty+r.month} className={i < premBordereaux.length - 1 ? 'border-b' : ''}>
                      <td className="px-4 py-3 font-medium">{r.treaty}</td>
                      <td className="px-4 py-3 text-muted-foreground">{r.month}</td>
                      <td className="px-4 py-3">{r.policies}</td>
                      <td className="px-4 py-3 tabular-nums">₦{r.grossPremium.toLocaleString()}</td>
                      <td className="px-4 py-3 tabular-nums font-medium text-primary">₦{r.cedingPremium.toLocaleString()}</td>
                      <td className="px-4 py-3 tabular-nums text-muted-foreground">₦{r.commission.toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </PageSection>

        <Separator />

        <PageSection title="Claims Bordereaux" description="Monthly ceded losses summary by treaty." actions={<Button variant="outline" size="sm">Export</Button>}>
          <Card>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <thead><tr className="border-b bg-muted/40">{['Treaty','Month','Claims','Gross Loss','Ceded Loss','Recovery'].map(h => <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>)}</tr></thead>
                <tbody>
                  {claimsBordereaux.map((r, i) => (
                    <tr key={r.treaty+r.month} className={i < claimsBordereaux.length - 1 ? 'border-b' : ''}>
                      <td className="px-4 py-3 font-medium">{r.treaty}</td>
                      <td className="px-4 py-3 text-muted-foreground">{r.month}</td>
                      <td className="px-4 py-3">{r.claims}</td>
                      <td className="px-4 py-3 tabular-nums">₦{r.grossLoss.toLocaleString()}</td>
                      <td className="px-4 py-3 tabular-nums font-medium text-destructive">₦{r.cededLoss.toLocaleString()}</td>
                      <td className="px-4 py-3 tabular-nums text-muted-foreground">{r.recovery > 0 ? `₦${r.recovery.toLocaleString()}` : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </PageSection>
      </TabsContent>

      {/* Recoveries */}
      <TabsContent value="recoveries" className="mt-4">
        <PageSection title="Reinsurance Recoveries" description="Track RI recovery on settled claims.">
          <Card>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <thead><tr className="border-b bg-muted/40">{['Claim','Treaty','Gross Paid','RI Share','RI Recovery','Status'].map(h => <th key={h} className="h-10 px-4 text-left text-xs font-semibold text-muted-foreground">{h}</th>)}</tr></thead>
                <tbody>
                  {recoveries.map((r, i) => (
                    <tr key={r.id} className={i < recoveries.length - 1 ? 'border-b' : ''}>
                      <td className="px-4 py-3 font-mono text-xs text-primary">{r.claimNumber}</td>
                      <td className="px-4 py-3">{r.treaty}</td>
                      <td className="px-4 py-3 tabular-nums">{r.grossPaid > 0 ? `₦${r.grossPaid.toLocaleString()}` : '—'}</td>
                      <td className="px-4 py-3">{r.riShare}%</td>
                      <td className="px-4 py-3 tabular-nums font-medium text-primary">{r.riRecovery > 0 ? `₦${r.riRecovery.toLocaleString()}` : '—'}</td>
                      <td className="px-4 py-3">
                        <Badge variant={r.status === 'RECOVERED' ? 'active' : 'pending'} className="text-[10px]">{r.status.toLowerCase()}</Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </PageSection>
      </TabsContent>

      {/* Returns */}
      <TabsContent value="returns" className="mt-4">
        <PageSection title="Quarterly Returns" description="Generate and submit reinsurance returns to each treaty participant." actions={<Button size="sm">Generate Return</Button>}>
          <div className="space-y-3">
            {[
              { period: 'Q1 2026 (Jan–Mar)', status: 'READY',     treaty: 'All Treaties' },
              { period: 'Q4 2025 (Oct–Dec)', status: 'SUBMITTED', treaty: 'All Treaties' },
              { period: 'Q3 2025 (Jul–Sep)', status: 'SUBMITTED', treaty: 'All Treaties' },
            ].map(r => (
              <div key={r.period} className="flex items-center justify-between rounded-lg border bg-card p-4">
                <div>
                  <p className="text-sm font-medium text-foreground">{r.period}</p>
                  <p className="text-xs text-muted-foreground">{r.treaty}</p>
                </div>
                <div className="flex items-center gap-3">
                  <Badge variant={r.status === 'READY' ? 'pending' : 'active'} className="text-[10px]">{r.status.toLowerCase()}</Badge>
                  <Button size="sm" variant="outline">{r.status === 'READY' ? 'Generate' : 'Download'}</Button>
                </div>
              </div>
            ))}
          </div>
        </PageSection>
      </TabsContent>
    </Tabs>
  );
}
