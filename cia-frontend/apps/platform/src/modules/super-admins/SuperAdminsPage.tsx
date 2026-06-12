import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button, PageHeader, PageSection, Skeleton, EmptyState, Badge, toast } from '@cia/ui';
import { useAuth } from '@cia/auth';
import { useSuperAdmins, useRevokeSuperAdmin, platformErrorCode, type SuperAdminSummary } from '@cia/api-client';
import InviteSuperAdminSheet from './InviteSuperAdminSheet';
import ConfirmActionDialog from '../../components/ConfirmActionDialog';

const REVOKE_ERR: Record<string, string> = {
  CANNOT_REVOKE_SELF: 'You cannot revoke your own super-admin access.',
  CANNOT_REVOKE_LAST_SUPER_ADMIN: 'Cannot revoke the last remaining super-admin.',
  SUPER_ADMIN_NOT_FOUND: 'That super-admin no longer exists.',
  KEYCLOAK_ADMIN_DISABLED: 'Super-admin management needs the Keycloak admin client enabled.',
};

export default function SuperAdminsPage() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const query = useSuperAdmins();
  const revoke = useRevokeSuperAdmin();
  const [inviteOpen, setInviteOpen] = useState(false);
  const [pending, setPending] = useState<SuperAdminSummary | null>(null);

  // Dashboard "Invite super-admin" deep-link → auto-open the sheet once.
  useEffect(() => {
    if (params.get('invite') === '1') {
      setInviteOpen(true);
      params.delete('invite');
      setParams(params, { replace: true });
    }
  }, [params, setParams]);

  const admins = query.data ?? [];
  const onlyOne = admins.length <= 1;
  // Best-effort self-match: AuthUser exposes only email/name (no Keycloak username), so this is a
  // UI hint only — the backend CANNOT_REVOKE_SELF guard is authoritative if the heuristic misses.
  const isSelf = (a: SuperAdminSummary) => a.username === user?.email || a.username === user?.name;

  async function run() {
    if (!pending) return;
    try {
      await revoke.mutateAsync(pending.username);
      toast({ title: 'Super-admin revoked', description: pending.username });
      setPending(null);
    } catch (err) {
      const code = platformErrorCode(err);
      toast({ variant: 'destructive', title: 'Revoke failed', description: (code && REVOKE_ERR[code]) || 'Unexpected error.' });
    }
  }

  // Distinguish the expected 503 (Keycloak admin client off) from a generic load failure, so the
  // empty state doesn't send operators down the wrong diagnostic path on a transient network error.
  if (query.isError) {
    const keycloakOff = platformErrorCode(query.error) === 'KEYCLOAK_ADMIN_DISABLED';
    return (
      <div className="p-6">
        <PageHeader title="Super-admins" />
        <EmptyState
          title={keycloakOff ? 'Super-admin management unavailable' : 'Couldn’t load super-admins'}
          description={keycloakOff
            ? 'This needs the Keycloak admin client enabled (cia.keycloak.admin.enabled=true).'
            : 'Something went wrong loading the list. If this persists, the Keycloak admin client may be disabled.'}
        />
      </div>
    );
  }

  return (
    <div className="p-6">
      <PageHeader
        title="Super-admins"
        description="Platform-realm accounts with cross-tenant SUPER_ADMIN."
        actions={<Button onClick={() => setInviteOpen(true)}>+ Invite super-admin</Button>}
      />

      <PageSection className="mt-4">
        {query.isLoading ? (
          <Skeleton className="h-48 w-full" />
        ) : (
          <div className="overflow-hidden rounded-lg border">
            <table className="w-full text-sm">
              <thead className="bg-secondary/40 text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 text-left">Username</th>
                  <th className="px-3 py-2 text-left">Email</th>
                  <th className="px-3 py-2 text-left">Status</th>
                  <th className="px-3 py-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {admins.map((a) => {
                  const self = isSelf(a);
                  const disabled = self || onlyOne;
                  return (
                    <tr key={a.username}>
                      <td className="px-3 py-2 font-medium text-foreground">{a.username}{self && <span className="ml-2 text-xs text-muted-foreground">(you)</span>}</td>
                      <td className="px-3 py-2">{a.email}</td>
                      <td className="px-3 py-2">
                        {a.enabled ? <Badge className="bg-primary/15 text-primary">Enabled</Badge> : <Badge className="bg-muted text-muted-foreground">Disabled</Badge>}
                      </td>
                      <td className="px-3 py-2 text-right">
                        <Button
                          variant="destructive" size="sm"
                          disabled={disabled}
                          title={self ? 'You cannot revoke your own access' : onlyOne ? 'Cannot revoke the last super-admin' : undefined}
                          onClick={() => setPending(a)}
                        >
                          Revoke
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </PageSection>

      <InviteSuperAdminSheet open={inviteOpen} onOpenChange={setInviteOpen} />
      <ConfirmActionDialog
        open={!!pending}
        onOpenChange={(o) => !o && setPending(null)}
        title="Revoke super-admin?"
        description={`Remove SUPER_ADMIN from ${pending?.username}? Their account stays but loses all platform access.`}
        confirmLabel="Revoke"
        destructive
        busy={revoke.isPending}
        onConfirm={run}
      />
    </div>
  );
}
