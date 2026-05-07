import React, { createContext, useContext, useEffect, useState } from 'react';
import { keycloak, initKeycloak, scheduleTokenRefresh } from './keycloak';

export interface AuthUser {
  id: string;
  email: string;
  name: string;
  roles: string[];
  authorities: string[];
  tenantId: string;
}

export interface AuthContextValue {
  user: AuthUser | null;
  token: string | undefined;
  isAuthenticated: boolean;
  logout: () => void;
  hasRole: (role: string) => boolean;
  hasAuthority: (authority: string) => boolean;
  hasAnyAuthority: (authorities: readonly string[]) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    initKeycloak().then((authenticated) => {
      if (authenticated && keycloak.tokenParsed) {
        const parsed = keycloak.tokenParsed as Record<string, unknown>;
        const roles = extractRoles(parsed);
        const authorities = buildAuthorities(parsed, roles);
        setUser({
          id:       String(parsed['sub'] ?? ''),
          email:    String(parsed['email'] ?? ''),
          name:     String(parsed['name'] ?? ''),
          roles,
          authorities,
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
        hasRole: (role) => hasAuthorityValue(user, toRoleAuthority(role)),
        hasAuthority: (authority) => hasAuthorityValue(user, authority),
        hasAnyAuthority: (authorities) => authorities.some((authority) => hasAuthorityValue(user, authority)),
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
      user: {
        id: 'dev',
        email: 'admin@nubeero.com',
        name: 'Akinwale Nubeero',
        roles: ['admin'],
        authorities: ['*'],
        tenantId: 'dev',
      },
      token: undefined,
      isAuthenticated: true,
      logout: () => {},
      hasRole: () => true,
      hasAuthority: () => true,
      hasAnyAuthority: () => true,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

function extractRoles(parsed: Record<string, unknown>): string[] {
  const realmAccess = parsed['realm_access'];
  if (!realmAccess || typeof realmAccess !== 'object') return [];

  const roles = (realmAccess as { roles?: unknown }).roles;
  if (!Array.isArray(roles)) return [];

  return roles
    .filter((role): role is string => typeof role === 'string')
    .map((role) => role.trim())
    .filter(Boolean);
}

function buildAuthorities(parsed: Record<string, unknown>, roles: string[]): string[] {
  const authorities = new Set<string>();

  for (const role of roles) {
    authorities.add(role);
    authorities.add(toRoleAuthority(role));
    const permission = toPermissionAuthority(role);
    if (permission) authorities.add(permission);
  }

  for (const scope of [...extractScopes(parsed['scope']), ...extractScopes(parsed['scp'])]) {
    authorities.add(scope);
    authorities.add(`SCOPE_${scope}`);
  }

  return [...authorities];
}

function extractScopes(claim: unknown): string[] {
  if (typeof claim === 'string') {
    return claim.split(/\s+/).map((scope) => scope.trim()).filter(Boolean);
  }

  if (Array.isArray(claim)) {
    return claim
      .filter((scope): scope is string => typeof scope === 'string')
      .map((scope) => scope.trim())
      .filter(Boolean);
  }

  return [];
}

function hasAuthorityValue(user: AuthUser | null, authority: string): boolean {
  if (!user) return false;
  return user.authorities.includes('*') || user.authorities.includes(authority);
}

function toRoleAuthority(authority: string): string {
  const normalized = stripRolePrefix(authority)
    .replace(/[:.-]/g, '_')
    .toUpperCase();
  return `ROLE_${normalized}`;
}

function toPermissionAuthority(authority: string): string | undefined {
  const normalized = stripRolePrefix(authority).trim();
  if (normalized.includes(':')) {
    return normalized.toLowerCase();
  }

  const separator = normalized.indexOf('_');
  if (separator < 1 || separator === normalized.length - 1) {
    return undefined;
  }

  const module = normalized.slice(0, separator).toLowerCase();
  const action = normalized.slice(separator + 1).toLowerCase();
  return `${module}:${action}`;
}

function stripRolePrefix(authority: string): string {
  return authority.toUpperCase().startsWith('ROLE_') ? authority.slice(5) : authority;
}
