import { useState } from 'react';
import {
  Button, Input, Label, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, toast,
} from '@cia/ui';
import { useInviteSuperAdmin, platformErrorCode, type InviteSuperAdminResponse } from '@cia/api-client';
import CredentialReveal from '../../components/CredentialReveal';

interface Props { open: boolean; onOpenChange: (open: boolean) => void; }

const ERR: Record<string, string> = {
  SUPER_ADMIN_ALREADY_EXISTS: 'A super-admin with that username already exists.',
  KEYCLOAK_ADMIN_DISABLED: 'Super-admin management needs the Keycloak admin client enabled.',
  VALIDATION_ERROR: 'Enter a username and a valid email.',
};

export default function InviteSuperAdminSheet({ open, onOpenChange }: Props) {
  const invite = useInviteSuperAdmin();
  const [result, setResult] = useState<InviteSuperAdminResponse | null>(null);
  const [form, setForm] = useState({ username: '', email: '' });

  function close() {
    setResult(null);
    setForm({ username: '', email: '' });
    onOpenChange(false);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    try {
      setResult(await invite.mutateAsync(form));
    } catch (err) {
      const code = platformErrorCode(err);
      toast({ variant: 'destructive', title: 'Invite failed', description: (code && ERR[code]) || 'Unexpected error.' });
    }
  }

  return (
    <Sheet open={open} onOpenChange={(o) => (o ? onOpenChange(true) : close())}>
      <SheetContent className="w-full sm:max-w-md">
        {!result ? (
          <>
            <SheetHeader>
              <SheetTitle>Invite super-admin</SheetTitle>
              <SheetDescription>Creates a platform-realm account with the SUPER_ADMIN role.</SheetDescription>
            </SheetHeader>
            <form onSubmit={submit} className="mt-4 space-y-3">
              <div className="space-y-1">
                <Label htmlFor="sa-username">Username</Label>
                <Input id="sa-username" value={form.username} onChange={(e) => setForm((f) => ({ ...f, username: e.target.value }))} required />
              </div>
              <div className="space-y-1">
                <Label htmlFor="sa-email">Email</Label>
                <Input id="sa-email" type="email" value={form.email} onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))} required />
              </div>
              <div className="flex justify-end pt-3">
                <Button type="submit" disabled={invite.isPending}>{invite.isPending ? 'Inviting…' : 'Invite →'}</Button>
              </div>
            </form>
          </>
        ) : (
          <>
            <SheetHeader>
              <SheetTitle>Super-admin invited</SheetTitle>
              <SheetDescription>{result.username} · {result.email}</SheetDescription>
            </SheetHeader>
            <div className="mt-4">
              <CredentialReveal
                title="Super-admin invited"
                subtitle={`${result.username} · ${result.email}`}
                identityLabel="Account"
                identityValue={`${result.username} · ${result.email}`}
                secret={result.temporaryPassword}
                onDone={close}
              />
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
