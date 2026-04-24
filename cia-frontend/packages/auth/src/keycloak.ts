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
  Object.assign(keycloak, config);
}

export async function initKeycloak(): Promise<boolean> {
  const onLoad = import.meta.env.DEV ? 'check-sso' : 'login-required';
  return keycloak.init({
    onLoad,
    checkLoginIframe: false,
    pkceMethod: 'S256',
    silentCheckSsoRedirectUri: import.meta.env.DEV
      ? undefined
      : `${window.location.origin}/silent-check-sso.html`,
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
