import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import App from './App';
import { PortalAuthProvider } from './app/auth/PortalAuthProvider';
import './app/globals.css';

const demoMode = import.meta.env.DEV || import.meta.env.VITE_DEMO_MODE === 'true';
const apiBase = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '';

configurePortalClient({ baseURL: apiBase, demoMode });

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: 1 } } });

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <PortalAuthProvider>
        <App />
      </PortalAuthProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
