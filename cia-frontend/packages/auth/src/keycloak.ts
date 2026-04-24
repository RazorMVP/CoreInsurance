import Keycloak from 'keycloak-js';

interface KeycloakConfig {
  url: string;
  realm: string;
  clientId: string;
}

const defaults: KeycloakConfig = {
  url:      'http://localhost:8180',
  realm:    'cia-dev',
  clientId: 'cia-back-office',
};

export const keycloak = new Keycloak(defaults);

export function configureKeycloak(config: Partial<KeycloakConfig>) {
  // Keycloak stores the URL as authServerUrl internally — assign both so the
  // instance uses the correct server regardless of keycloak-js version.
  if (config.url) (keycloak as unknown as Record<string, unknown>).authServerUrl = config.url;
  Object.assign(keycloak, config);
}

export async function initKeycloak(): Promise<boolean> {
  // 'login-required' redirects the browser to the Keycloak login page.
  // Only use it when Keycloak is actually configured; otherwise use
  // 'check-sso' which silently checks for an existing session without redirect.
  const onLoad = import.meta.env.VITE_KEYCLOAK_URL ? 'login-required' : 'check-sso';
  return keycloak.init({
    onLoad,
    checkLoginIframe: false,
    pkceMethod: 'S256',
  });
}

export function scheduleTokenRefresh() {
  setInterval(async () => {
    try {
      await keycloak.updateToken(60);
    } catch {
      keycloak.logout();
    }
  }, 30_000);
}
