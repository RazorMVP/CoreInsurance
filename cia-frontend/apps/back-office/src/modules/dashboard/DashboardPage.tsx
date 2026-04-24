import { Badge } from '@cia/ui';

interface StatCard {
  label:  string;
  value:  string;
  delta:  string;
  trend:  'up' | 'down' | 'neutral';
}

const stats: StatCard[] = [
  { label: 'Active Policies',   value: '—',    delta: 'Loading',  trend: 'neutral' },
  { label: 'Open Claims',       value: '—',    delta: 'Loading',  trend: 'neutral' },
  { label: 'Pending Approvals', value: '—',    delta: 'Loading',  trend: 'neutral' },
  { label: 'Premiums (MTD)',    value: '₦—',   delta: 'Loading',  trend: 'neutral' },
];

const recentActivity = [
  { id: 1, description: 'Policy POL-2026-00041 approved',   time: '2m ago',  status: 'active' as const },
  { id: 2, description: 'Claim CLM-2026-00018 registered',  time: '14m ago', status: 'pending' as const },
  { id: 3, description: 'Quote QUO-2026-00092 submitted',   time: '1h ago',  status: 'pending' as const },
  { id: 4, description: 'Endorsement END-2026-00007 approved', time: '2h ago', status: 'active' as const },
  { id: 5, description: 'Receipt REC-2026-00034 posted',    time: '3h ago',  status: 'active' as const },
];

export default function DashboardPage() {
  return (
    <div className="p-6 space-y-6">
      {/* Stats row */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {stats.map((stat) => (
          <div
            key={stat.label}
            className="rounded-lg bg-card p-5"
            style={{ boxShadow: '0 0 0 1px var(--border)' }}
          >
            <p className="text-xs font-medium text-muted-foreground">{stat.label}</p>
            <p className="mt-2 font-display text-2xl font-700 tracking-tight text-foreground">
              {stat.value}
            </p>
            <p className="mt-1 text-xs text-muted-foreground">{stat.delta}</p>
          </div>
        ))}
      </div>

      {/* Recent activity */}
      <div
        className="rounded-lg bg-card"
        style={{ boxShadow: '0 0 0 1px var(--border)' }}
      >
        <div className="flex items-center justify-between px-5 py-4" style={{ boxShadow: '0 1px 0 var(--border)' }}>
          <h2 className="font-display text-sm font-600 text-foreground">Recent Activity</h2>
          <span className="text-xs text-muted-foreground">Today</span>
        </div>
        <ul>
          {recentActivity.map((item, i) => (
            <li
              key={item.id}
              className="flex items-center gap-4 px-5 py-3.5"
              style={i < recentActivity.length - 1 ? { boxShadow: '0 1px 0 var(--border)' } : undefined}
            >
              <Badge variant={item.status}>{item.status}</Badge>
              <p className="flex-1 text-sm text-foreground">{item.description}</p>
              <span className="shrink-0 text-xs text-muted-foreground">{item.time}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
