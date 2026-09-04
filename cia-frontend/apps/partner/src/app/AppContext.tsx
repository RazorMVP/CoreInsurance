import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { useApps, type PortalAppSummaryDto } from '@cia/api-client';

const LS_KEY = 'cia.portal.selectedAppId';

interface SelectedAppValue {
  apps: PortalAppSummaryDto[];
  selectedAppId: string | null;
  selectedApp: PortalAppSummaryDto | undefined;
  setSelectedAppId: (id: string) => void;
  isLoading: boolean;
  isEmpty: boolean;
}
const Ctx = createContext<SelectedAppValue | null>(null);

function readStored(): string | null {
  try { return localStorage.getItem(LS_KEY); } catch { return null; }
}

export function AppContextProvider({ children }: { children: React.ReactNode }) {
  const appsQuery = useApps();
  const apps = useMemo(() => appsQuery.data ?? [], [appsQuery.data]);
  const [selectedAppId, setSelected] = useState<string | null>(readStored);

  // Reconcile once apps load: keep a valid stored id, else auto-select a lone app.
  useEffect(() => {
    if (apps.length === 0) return;
    const stillValid = selectedAppId && apps.some((a) => a.partnerAppId === selectedAppId);
    if (!stillValid) {
      const next = apps.length === 1 ? apps[0].partnerAppId : null;
      setSelected(next);
      try { if (next) localStorage.setItem(LS_KEY, next); } catch { /* ignore */ }
    }
  }, [apps, selectedAppId]);

  const setSelectedAppId = (id: string) => {
    setSelected(id);
    try { localStorage.setItem(LS_KEY, id); } catch { /* ignore */ }
  };

  const value: SelectedAppValue = {
    apps,
    selectedAppId,
    selectedApp: apps.find((a) => a.partnerAppId === selectedAppId),
    setSelectedAppId,
    isLoading: appsQuery.isLoading,
    isEmpty: !appsQuery.isLoading && apps.length === 0,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSelectedApp(): SelectedAppValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSelectedApp must be used within AppContextProvider');
  return v;
}
