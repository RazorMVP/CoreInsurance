import Keycloak from 'keycloak-js';

interface KeycloakConfig {
  url: string;
  realm: string;
  clientId: string;
}

const defaults: KeycloakConfig = {
  url:      'http://localhost:8280',
  realm:    'cia',
  clientId: 'cia-back-office',
};

let activeConfig: KeycloakConfig = { ...defaults };

// `let` (not `const`) so configureKeycloak can REPLACE the instance. ESM live
// bindings mean consumers that `import { keycloak }` see the new instance at
// call time. keycloak-js v26 captures its config at construction and ignores
// later property mutation, so we must reconstruct rather than Object.assign.
export let keycloak = new Keycloak(activeConfig);

export function configureKeycloak(config: Partial<KeycloakConfig>) {
  activeConfig = { ...activeConfig, ...config };
  keycloak = new Keycloak(activeConfig);
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
