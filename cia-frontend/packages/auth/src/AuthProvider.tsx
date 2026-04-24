import React, { createContext, useContext, useEffect, useState } from 'react';
import { keycloak, initKeycloak, scheduleTokenRefresh } from './keycloak';

interface AuthUser {
  id: string;
  email: string;
  name: string;
  roles: string[];
  tenantId: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  token: string | undefined;
  isAuthenticated: boolean;
  logout: () => void;
  hasRole: (role: string) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    initKeycloak().then((authenticated) => {
      if (authenticated && keycloak.tokenParsed) {
        const parsed = keycloak.tokenParsed as Record<string, unknown>;
        setUser({
          id:       String(parsed['sub'] ?? ''),
          email:    String(parsed['email'] ?? ''),
          name:     String(parsed['name'] ?? ''),
          roles:    (parsed['realm_access'] as { roles?: string[] })?.roles ?? [],
          tenantId: String(parsed['tenant_id'] ?? ''),
        });
        scheduleTokenRefresh();
      }
      setReady(true);
    }).catch(() => {
      // Keycloak unreachable (local dev without auth stack running)
      setReady(true);
    });
  }, []);

  if (!ready) return null;

  return (
    <AuthContext.Provider
      value={{
        user,
        token: keycloak.token,
        isAuthenticated: !!user,
        logout: () => keycloak.logout(),
        hasRole: (role) => user?.roles.includes(role) ?? false,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within <AuthProvider>');
  return ctx;
}

export function DevAuthProvider({ children }: { children: React.ReactNode }) {
  return (
    <AuthContext.Provider value={{
      user: { id: 'dev', email: 'admin@nubeero.com', name: 'Akinwale Nubeero', roles: ['admin'], tenantId: 'dev' },
      token: undefined,
      isAuthenticated: true,
      logout: () => {},
      hasRole: () => true,
    }}>
      {children}
    </AuthContext.Provider>
  );
}
