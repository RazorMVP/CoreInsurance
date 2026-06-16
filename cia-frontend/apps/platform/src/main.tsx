import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, DevAuthProvider, keycloak, configureKeycloak } from '@cia/auth';
import { initApiClient, setTokenGetter } from '@cia/api-client';
import App from './App';
import './app/globals.css';

initApiClient(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080');

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 1 } },
});

// The platform app authenticates against the `platform` Keycloak realm with the
// `cia-platform` client. We REUSE the VITE_KEYCLOAK_* env names (scoped per-deployment)
// because @cia/auth's initKeycloak keys onLoad:'login-required' off VITE_KEYCLOAK_URL.
const keycloakConfigured = !!import.meta.env.VITE_KEYCLOAK_URL;
const demoMode = import.meta.env.VITE_DEMO_MODE === 'true';

if (keycloakConfigured) {
  configureKeycloak({
    url:      import.meta.env.VITE_KEYCLOAK_URL,
    realm:    import.meta.env.VITE_KEYCLOAK_REALM     ?? 'platform',
    clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'cia-platform',
  });
  setTokenGetter(() => keycloak.token);
} else if (!import.meta.env.DEV && !demoMode) {
  throw new Error(
    'VITE_KEYCLOAK_URL is required for production builds of the platform console. ' +
    'Set the platform realm Keycloak vars, or VITE_DEMO_MODE=true for a mocked demo build.'
  );
}

const AuthWrapper = keycloakConfigured ? AuthProvider : DevAuthProvider;

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AuthWrapper>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </AuthWrapper>
  </React.StrictMode>
);
