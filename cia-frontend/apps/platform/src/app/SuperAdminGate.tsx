import type { ReactNode } from 'react';
import { useAuth } from '@cia/auth';
import NotAuthorized from './NotAuthorized';

/**
 * Defense-in-depth UX gate: the backend's @PreAuthorize + assertPlatformRealm is the real
 * boundary; this just avoids rendering the console to a non-super-admin token.
 */
export default function SuperAdminGate({ children }: { children: ReactNode }) {
  const { hasRole } = useAuth();
  if (!hasRole('SUPER_ADMIN')) return <NotAuthorized />;
  return <>{children}</>;
}
