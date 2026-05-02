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

initApiClient(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080');

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 1 } },
});

const keycloakConfigured = !!import.meta.env.VITE_KEYCLOAK_URL;

if (keycloakConfigured) {
  configureKeycloak({
    url:      import.meta.env.VITE_KEYCLOAK_URL,
    realm:    import.meta.env.VITE_KEYCLOAK_REALM     ?? 'cia-dev',
    clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'cia-back-office',
  });
  setTokenGetter(() => keycloak.token);
} else if (!import.meta.env.DEV) {
  // Production builds MUST have Keycloak configured. Fail loud rather than
  // silently fall back to DevAuthProvider, which would ship unauthenticated
  // mock access to end users.
  throw new Error(
    'VITE_KEYCLOAK_URL is required for production builds. ' +
    'Configure Keycloak environment variables on your hosting provider.'
  );
}

// DevAuthProvider is dev-only — never reached in production due to the throw above.
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
