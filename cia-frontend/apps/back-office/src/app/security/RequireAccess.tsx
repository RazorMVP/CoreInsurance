import { useAuth } from '@cia/auth';
import { Button } from '@cia/ui';
import type React from 'react';
import type { AccessRule } from './access-control';

interface RequireAccessProps {
  anyAuthority?: AccessRule;
  children: React.ReactNode;
}

export default function RequireAccess({ anyAuthority = [], children }: RequireAccessProps) {
  const { isAuthenticated, hasAnyAuthority } = useAuth();

  if (!isAuthenticated) {
    return null;
  }

  if (anyAuthority.length > 0 && !hasAnyAuthority(anyAuthority)) {
    return (
      <div className="flex min-h-[360px] flex-col items-center justify-center gap-3 px-6 text-center">
        <h2 className="font-display text-xl font-semibold text-foreground">Access denied</h2>
        <p className="max-w-sm text-sm text-muted-foreground">
          Your account does not have access to this area.
        </p>
        <Button variant="outline" onClick={() => window.history.back()}>
          Go back
        </Button>
      </div>
    );
  }

  return children;
}
