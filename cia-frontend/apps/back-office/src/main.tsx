import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, DevAuthProvider, keycloak, configureKeycloak } from '@cia/auth';
import { initApiClient, setTokenGetter } from '@cia/api-client';
import App from './App';
import './app/globals.css';

// Devtools are lazy-imported so Rollup tree-shakes the package out of the
// production bundle. import.meta.env.DEV is a compile-time constant — Rollup
// replaces it with `false` in prod and eliminates the dead import branch.
const ReactQueryDevtools = import.meta.env.DEV
  ? React.lazy(() =>
      import('@tanstack/react-query-devtools').then((m) => ({
        default: m.ReactQueryDevtools,
      }))
    )
  : () => null;

initApiClient(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8090');

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 1 } },
});

const keycloakConfigured = !!import.meta.env.VITE_KEYCLOAK_URL;

// Demo mode (`VITE_DEMO_MODE=true`) allows production builds to ship with
// the DevAuthProvider — used for stakeholder previews on the public Vercel
// URL while real Keycloak infrastructure is not yet stood up. Auth is
// mocked, mutations hit the (likely also-mocked) backend at the configured
// API base URL. NEVER enable for tenant-bearing environments.
const demoMode = import.meta.env.VITE_DEMO_MODE === 'true';

if (keycloakConfigured) {
  configureKeycloak({
    url:      import.meta.env.VITE_KEYCLOAK_URL,
    realm:    import.meta.env.VITE_KEYCLOAK_REALM     ?? 'cia-dev',
    clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'cia-back-office',
  });
  setTokenGetter(() => keycloak.token);
} else if (!import.meta.env.DEV && !demoMode) {
  // Production builds MUST have Keycloak configured. Fail loud rather than
  // silently fall back to DevAuthProvider, which would ship unauthenticated
  // mock access to end users. The only exception is VITE_DEMO_MODE=true,
  // which is for the stakeholder-preview Vercel URL — never for real tenants.
  throw new Error(
    'VITE_KEYCLOAK_URL is required for production builds. ' +
    'Configure Keycloak environment variables on your hosting provider, ' +
    'or set VITE_DEMO_MODE=true for a demo build with mocked auth.'
  );
}

// DevAuthProvider is used in dev mode and demo mode; AuthProvider only when
// real Keycloak is configured.
const AuthWrapper = keycloakConfigured ? AuthProvider : DevAuthProvider;

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AuthWrapper>
      <QueryClientProvider client={queryClient}>
        <App />
        <ReactQueryDevtools initialIsOpen={false} />
      </QueryClientProvider>
    </AuthWrapper>
  </React.StrictMode>
);
