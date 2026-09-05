import { createContext, useContext, useEffect, useRef } from 'react';
import { useSession, useLogout, setPortalCsrfToken, isPortalDemoMode, type PortalMeDto } from '@cia/api-client';
import { LoginScreen } from './LoginScreen';

interface PortalAuthValue { session: PortalMeDto; demoMode: boolean; logout: () => void; }
const Ctx = createContext<PortalAuthValue | null>(null);

const API_BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '';

export function PortalAuthProvider({ children }: { children: React.ReactNode }) {
  const sessionQuery = useSession();
  const logoutMutation = useLogout();

  // Keep the CSRF token in the api-client for mutating requests.
  useEffect(() => {
    setPortalCsrfToken(sessionQuery.data?.csrfToken ?? null);
  }, [sessionQuery.data?.csrfToken]);

  // A live 401 (session expired mid-app) forces the login screen — but once the
  // session is already known-invalid, a 401 is expected (not a fresh expiry), so
  // the handler must be a no-op or a logged-out visitor loops `getMe()` forever
  // (portal.ts's response interceptor dispatches this event on every 401).
  const isErrorRef = useRef(false);
  useEffect(() => { isErrorRef.current = sessionQuery.isError; }, [sessionQuery.isError]);

  const refetch = sessionQuery.refetch;
  useEffect(() => {
    const onUnauth = () => { if (!isErrorRef.current) refetch(); };
    window.addEventListener('portal:unauthorized', onUnauth);
    return () => window.removeEventListener('portal:unauthorized', onUnauth);
  }, [refetch]);

  if (sessionQuery.isLoading) {
    return <div className="flex min-h-screen items-center justify-center bg-background text-sm text-muted-foreground">Loading…</div>;
  }
  if (sessionQuery.isError || !sessionQuery.data) {
    return <LoginScreen apiBase={API_BASE} />;
  }

  const logout = () => {
    logoutMutation.mutate(undefined, {
      onSuccess: (res) => { window.location.href = isPortalDemoMode() ? '/' : res.logoutUrl; },
    });
  };

  return <Ctx.Provider value={{ session: sessionQuery.data, demoMode: isPortalDemoMode(), logout }}>{children}</Ctx.Provider>;
}

export function usePortalAuth(): PortalAuthValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('usePortalAuth must be used within PortalAuthProvider');
  return v;
}
