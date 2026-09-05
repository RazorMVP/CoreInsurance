import { usePortalAuth } from '../auth/PortalAuthProvider';
import { useSelectedApp } from '../AppContext';

export function Topbar() {
  const { demoMode } = usePortalAuth();
  const { apps, selectedAppId, setSelectedAppId, isEmpty } = useSelectedApp();
  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-border bg-card px-4">
      <div className="flex-1">
        {!isEmpty && (
          <label className="flex items-center gap-2 text-sm text-muted-foreground">
            App:
            <select
              value={selectedAppId ?? ''}
              onChange={(e) => setSelectedAppId(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm text-foreground"
            >
              {selectedAppId === null && <option value="" disabled>Select an app…</option>}
              {apps.map((a) => (
                <option key={a.partnerAppId} value={a.partnerAppId}>{a.tenantLabel} · {a.clientId}</option>
              ))}
            </select>
          </label>
        )}
      </div>
      {demoMode && (
        <span className="rounded-full bg-amber-500/20 px-2 py-0.5 text-xs font-medium text-amber-400">Demo</span>
      )}
    </header>
  );
}
