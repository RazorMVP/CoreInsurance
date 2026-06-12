import { Badge } from '@cia/ui';
import type { PlatformAuditEntry } from '@cia/api-client';

const ACTION_TONE: Record<string, string> = {
  ONBOARD: 'bg-primary/15 text-primary',
  ACTIVATE: 'bg-primary/15 text-primary',
  SUSPEND: 'bg-amber-500/15 text-amber-400',
  INVITE_SUPER_ADMIN: 'bg-sky-500/15 text-sky-400',
  REVOKE_SUPER_ADMIN: 'bg-rose-500/15 text-rose-400',
};

export default function AuditTable({ rows }: { rows: PlatformAuditEntry[] }) {
  if (rows.length === 0) {
    return <p className="px-1 py-6 text-center text-sm text-muted-foreground">No activity yet.</p>;
  }
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-secondary/40 text-xs text-muted-foreground">
          <tr>
            <th className="px-3 py-2 text-left">Action</th>
            <th className="px-3 py-2 text-left">Target</th>
            <th className="px-3 py-2 text-left">Actor</th>
            <th className="px-3 py-2 text-left">When</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {rows.map((r) => (
            <tr key={r.id}>
              <td className="px-3 py-2"><Badge className={ACTION_TONE[r.action] ?? 'bg-secondary text-foreground'}>{r.action}</Badge></td>
              <td className="px-3 py-2 font-mono text-xs">{r.targetSchema ?? detailName(r.detail)}</td>
              <td className="px-3 py-2">{r.actorUsername} <span className="text-muted-foreground">@ {r.actorRealm}</span></td>
              <td className="px-3 py-2 text-muted-foreground">{new Date(r.at).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** For user-targeted rows (null targetSchema), show the username from the detail JSON. */
function detailName(detail: string | null): string {
  if (!detail) return '—';
  try { return (JSON.parse(detail) as { username?: string }).username ?? '—'; } catch { return '—'; }
}
