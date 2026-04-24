import {
  Badge, Button, PageSection, Separator,
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';

// ── Shared helpers ────────────────────────────────────────────────────────────
function Table({ headers, rows }: { headers: string[]; rows: (string | number)[][] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-muted/40">
            {headers.map(h => (
              <th key={h} className="h-9 px-4 text-left text-xs font-semibold text-muted-foreground whitespace-nowrap">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className={i < rows.length - 1 ? 'border-b' : ''}>
              {row.map((cell, j) => (
                <td key={j} className="px-4 py-3 text-sm text-foreground whitespace-nowrap">{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ExportButton() {
  return (
    <Button variant="outline" size="sm" onClick={() => {
      // TODO: GET /api/v1/audit/reports/{type}/export
    }}>
      Export CSV
    </Button>
  );
}

// ── Report data (replace with useList hooks) ─────────────────────────────────

const actionsByUser = [
  ['1', 'Akinwale Nubeero', '145', '42', '67', '8', '28', 'Today'],
  ['2', 'Adaeze Nwosu',    '98',  '31', '54', '0', '13', 'Yesterday'],
  ['3', 'Emeka Eze',       '64',  '18', '39', '1',  '6', '2 days ago'],
  ['4', 'Ngozi Adeyemi',   '41',  '12', '24', '0',  '5', '3 days ago'],
  ['5', 'Chukwudi Obi',    '27',   '8', '15', '0',  '4', '5 days ago'],
];

const actionsByModule = [
  ['Policies',     '287', '12', '68', '287'],
  ['Claims',       '234',  '8', '52', '234'],
  ['Customers',    '186',  '5', '41', '186'],
  ['Endorsements', '112',  '3', '28', '112'],
  ['Finance',       '89',  '4', '21',  '89'],
  ['Reinsurance',   '67',  '2', '15',  '67'],
  ['Quotation',     '54',  '1', '12',  '54'],
];

const approvalTrail = [
  ['POL-2026-00001', 'Policy',      '₦78,750',    'Adaeze Nwosu',    'Akinwale Nubeero', '2026-02-01 10:05'],
  ['POL-2026-00002', 'Policy',      '₦115,000',   'Adaeze Nwosu',    'Akinwale Nubeero', '2026-03-01 11:30'],
  ['CLM-2026-00001', 'Claim',       '₦850,000',   'Adaeze Nwosu',    'Akinwale Nubeero', '2026-03-22 09:07'],
  ['CLM-2026-00004', 'Claim',       '₦450,000',   'Emeka Eze',       'Akinwale Nubeero', '2026-03-05 14:00'],
  ['END-2026-00001', 'Endorsement', '₦5,000',     'Adaeze Nwosu',    'Akinwale Nubeero', '2026-02-16 09:55'],
  ['REC-2026-00001', 'Receipt',     '₦40,000',    'Emeka Eze',       'Akinwale Nubeero', '2026-03-01 14:10'],
  ['PAY-2026-00001', 'Payment',     '₦12,500',    'Emeka Eze',       'Akinwale Nubeero', '2026-02-25 16:22'],
];

const dataChanges = [
  ['POL-2026-00001', 'status',             'PENDING_APPROVAL', 'ACTIVE',      'Akinwale Nubeero', '2026-02-01 10:05'],
  ['CLM-2026-00001', 'status',             'REGISTERED',       'PROCESSING',  'Adaeze Nwosu',     '2026-03-14 15:44'],
  ['CLM-2026-00001', 'reserveAmount',      '0',                '650,000',     'Adaeze Nwosu',     '2026-03-14 15:44'],
  ['CLM-2026-00001', 'status',             'PROCESSING',       'APPROVED',    'Akinwale Nubeero', '2026-03-22 09:07'],
  ['USER:u3',        'accessGroupId',      'ag2',              'ag3',         'Akinwale Nubeero', '2026-02-10 08:47'],
  ['END-2026-00001', 'newSumInsured',      '3,500,000',        '4,000,000',   'Adaeze Nwosu',     '2026-02-15 14:02'],
  ['CUSTOMER:c1',    'kycStatus',          'PENDING',          'VERIFIED',    'Akinwale Nubeero', '2026-01-30 10:00'],
];

const loginSecurity = [
  ['Akinwale Nubeero', 'akinwale@nubeero.com',      '47', '0', 'Today 08:01',   'Low'],
  ['Adaeze Nwosu',     'adaeze@nubeero.com',         '31', '1', 'Today 08:15',   'Low'],
  ['Emeka Eze',        'emeka.eze@nubeero.com',       '18', '3', 'Today 08:23',   'High'],
  ['Ngozi Adeyemi',    'ngozi.adeyemi@nubeero.com',  '22', '0', 'Yesterday',     'Low'],
  ['Chukwudi Obi',     'chukwudi.obi@nubeero.com',    '9', '0', '5 days ago',    'Low'],
];

const userActivity = [
  ['1', 'Akinwale Nubeero', '145', 'UPDATE',  '98'],
  ['2', 'Adaeze Nwosu',     '98',  'UPDATE',  '82'],
  ['3', 'Emeka Eze',        '64',  'UPDATE',  '61'],
  ['4', 'Ngozi Adeyemi',    '41',  'CREATE',  '44'],
  ['5', 'Chukwudi Obi',     '27',  'CREATE',  '33'],
];

const RISK_VARIANT: Record<string, 'active'|'pending'|'rejected'> = {
  Low: 'active', Medium: 'pending', High: 'rejected',
};

export default function ReportsTab() {
  return (
    <Tabs defaultValue="actions-by-user">
      <TabsList className="flex-wrap h-auto gap-1">
        <TabsTrigger value="actions-by-user"   className="text-xs">Actions by User</TabsTrigger>
        <TabsTrigger value="actions-by-module" className="text-xs">Actions by Module</TabsTrigger>
        <TabsTrigger value="approval-trail"    className="text-xs">Approval Trail</TabsTrigger>
        <TabsTrigger value="data-changes"      className="text-xs">Data Changes</TabsTrigger>
        <TabsTrigger value="login-security"    className="text-xs">Login Security</TabsTrigger>
        <TabsTrigger value="user-activity"     className="text-xs">User Activity</TabsTrigger>
      </TabsList>

      <TabsContent value="actions-by-user" className="mt-4">
        <PageSection
          title="Actions by User"
          description="Total system activity ranked by user. Covers all modules."
          actions={<ExportButton />}
        >
          <Table
            headers={['Rank', 'User', 'Total', 'Creates', 'Updates', 'Deletes', 'Approvals', 'Last Active']}
            rows={actionsByUser}
          />
        </PageSection>
      </TabsContent>

      <TabsContent value="actions-by-module" className="mt-4">
        <PageSection
          title="Actions by Module"
          description="Event volume per module — helps identify the most active areas of the system."
          actions={<ExportButton />}
        >
          <Table
            headers={['Module', 'Total', 'Today', 'This Week', 'This Month']}
            rows={actionsByModule}
          />
        </PageSection>
      </TabsContent>

      <TabsContent value="approval-trail" className="mt-4">
        <PageSection
          title="Approval Audit Trail"
          description="Record of all approval decisions — what was approved, by whom, and when."
          actions={<ExportButton />}
        >
          <Table
            headers={['Entity', 'Type', 'Amount', 'Submitted By', 'Approved By', 'Approved At']}
            rows={approvalTrail}
          />
        </PageSection>
      </TabsContent>

      <TabsContent value="data-changes" className="mt-4">
        <PageSection
          title="Data Change History"
          description="Field-level changes across all entities — before and after values."
          actions={<ExportButton />}
        >
          <Table
            headers={['Entity', 'Field', 'Old Value', 'New Value', 'Changed By', 'Timestamp']}
            rows={dataChanges}
          />
        </PageSection>
      </TabsContent>

      <TabsContent value="login-security" className="mt-4">
        <PageSection
          title="Login Security Report"
          description="Authentication health per user — successful vs failed attempts, last seen."
          actions={<ExportButton />}
        >
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b bg-muted/40">
                  {['User', 'Email', 'Successful', 'Failed', 'Last Login', 'Risk'].map(h => (
                    <th key={h} className="h-9 px-4 text-left text-xs font-semibold text-muted-foreground whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {loginSecurity.map(([user, email, ok, fail, last, risk], i) => (
                  <tr key={i} className={i < loginSecurity.length - 1 ? 'border-b' : ''}>
                    <td className="px-4 py-3 font-medium">{user}</td>
                    <td className="px-4 py-3 text-muted-foreground">{email}</td>
                    <td className="px-4 py-3 text-primary font-medium">{ok}</td>
                    <td className="px-4 py-3 text-destructive">{fail}</td>
                    <td className="px-4 py-3 text-muted-foreground">{last}</td>
                    <td className="px-4 py-3">
                      <Badge variant={RISK_VARIANT[risk as string] ?? 'draft'} className="text-[10px]">{risk}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </PageSection>
      </TabsContent>

      <TabsContent value="user-activity" className="mt-4">
        <PageSection
          title="User Activity Summary"
          description="Ranked activity summary — total operations, most common action, and activity score."
          actions={<ExportButton />}
        >
          <Table
            headers={['Rank', 'User', 'Total Actions', 'Most Common Action', 'Activity Score']}
            rows={userActivity}
          />
          <Separator className="my-4" />
          <p className="text-xs text-muted-foreground">
            Activity score is weighted: approvals (×3), creates (×2), updates (×1), deletes (×1). Calculated over the last 30 days.
          </p>
        </PageSection>
      </TabsContent>
    </Tabs>
  );
}
