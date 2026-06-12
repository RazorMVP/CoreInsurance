/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_KEYCLOAK_URL: string;
  readonly VITE_KEYCLOAK_REALM: string;
  readonly VITE_KEYCLOAK_CLIENT_ID: string;
  /** When 'true', allows production builds to use DevAuthProvider (mocked auth) and renders a Demo banner. NEVER enable for live platform environments. */
  readonly VITE_DEMO_MODE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
