import {
  Badge, PageHeader, StatCard,
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import AuditLogTab  from './audit-log/AuditLogTab';
import LoginLogTab  from './login-log/LoginLogTab';
import ReportsTab   from './reports/ReportsTab';
import AlertsTab    from './alerts/AlertsTab';

export default function AuditPage() {
  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Audit & Compliance"
        description="System-wide activity log, login history, pre-built reports and real-time security alerts."
        actions={
          <Badge variant="pending" className="text-xs">System Auditor — read-only</Badge>
        }
      />

      {/* Summary stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard label="Events Today"        value="47"  sub="All modules" />
        <StatCard label="Failed Logins (24h)" value="3"   sub="From 2 users" />
        <StatCard label="Open Alerts"         value="3"   sub="Require acknowledgement" />
        <StatCard label="Data Changes (7d)"   value="28"  sub="Across all entities" />
      </div>

      <Tabs defaultValue="audit-log">
        <TabsList>
          <TabsTrigger value="audit-log">Audit Log</TabsTrigger>
          <TabsTrigger value="login-log">
            Login &amp; Sessions
          </TabsTrigger>
          <TabsTrigger value="reports">Reports</TabsTrigger>
          <TabsTrigger value="alerts">
            Alerts
            <span className="ml-1.5 rounded-full bg-[var(--status-rejected-bg)] text-[var(--status-rejected-fg)] px-1.5 py-0.5 text-[10px] font-semibold">
              3
            </span>
          </TabsTrigger>
        </TabsList>

        <TabsContent value="audit-log" className="mt-4">
          <AuditLogTab />
        </TabsContent>

        <TabsContent value="login-log" className="mt-4">
          <LoginLogTab />
        </TabsContent>

        <TabsContent value="reports" className="mt-4">
          <ReportsTab />
        </TabsContent>

        <TabsContent value="alerts" className="mt-4">
          <AlertsTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
