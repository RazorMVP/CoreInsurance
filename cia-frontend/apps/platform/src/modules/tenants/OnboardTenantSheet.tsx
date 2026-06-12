import { useState } from 'react';
import {
  Button, Input, Label, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, toast,
} from '@cia/ui';
import { useOnboardTenant, platformErrorCode, type OnboardTenantResponse } from '@cia/api-client';
import CredentialReveal from '../../components/CredentialReveal';

interface Props { open: boolean; onOpenChange: (open: boolean) => void; }

const ERR: Record<string, string> = {
  TENANT_ALREADY_EXISTS: 'A tenant with that schema or subdomain already exists.',
  REALM_SCHEMA_MISMATCH: 'Realm must equal schema — leave realm blank to default it.',
  VALIDATION_ERROR: 'Check the field formats (schema/subdomain are lowercase identifiers).',
};

export default function OnboardTenantSheet({ open, onOpenChange }: Props) {
  const onboard = useOnboardTenant();
  const [result, setResult] = useState<OnboardTenantResponse | null>(null);
  const [form, setForm] = useState({ schema: '', displayName: '', subdomain: '', adminUsername: '', adminEmail: '' });

  function set<K extends keyof typeof form>(k: K, v: string) { setForm((f) => ({ ...f, [k]: v })); }

  function close() {
    setResult(null);
    setForm({ schema: '', displayName: '', subdomain: '', adminUsername: '', adminEmail: '' });
    onOpenChange(false);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    try {
      const resp = await onboard.mutateAsync(form);
      setResult(resp);
    } catch (err) {
      const code = platformErrorCode(err);
      toast({ variant: 'destructive', title: 'Onboard failed', description: (code && ERR[code]) || 'Unexpected error.' });
    }
  }

  return (
    <Sheet open={open} onOpenChange={(o) => (o ? onOpenChange(true) : close())}>
      <SheetContent className="w-full sm:max-w-md">
        {!result ? (
          <>
            <SheetHeader>
              <SheetTitle>Onboard tenant</SheetTitle>
              <SheetDescription>Provisions schema + Keycloak realm + first admin.</SheetDescription>
            </SheetHeader>
            <form onSubmit={submit} className="mt-4 space-y-3">
              <div className="space-y-1">
                <Label htmlFor="schema">Schema <span className="text-muted-foreground">(realm = schema)</span></Label>
                <Input id="schema" value={form.schema} onChange={(e) => set('schema', e.target.value)} placeholder="tenant_acme" required />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <Label htmlFor="displayName">Display name</Label>
                  <Input id="displayName" value={form.displayName} onChange={(e) => set('displayName', e.target.value)} placeholder="Acme Insurance" required />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="subdomain">Subdomain</Label>
                  <Input id="subdomain" value={form.subdomain} onChange={(e) => set('subdomain', e.target.value)} placeholder="acme" required />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <Label htmlFor="adminUsername">Admin username</Label>
                  <Input id="adminUsername" value={form.adminUsername} onChange={(e) => set('adminUsername', e.target.value)} placeholder="admin" required />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="adminEmail">Admin email</Label>
                  <Input id="adminEmail" type="email" value={form.adminEmail} onChange={(e) => set('adminEmail', e.target.value)} placeholder="admin@acme.test" required />
                </div>
              </div>
              <div className="flex justify-end pt-3">
                <Button type="submit" disabled={onboard.isPending}>{onboard.isPending ? 'Onboarding…' : 'Onboard →'}</Button>
              </div>
            </form>
          </>
        ) : (
          <>
            <SheetHeader>
              <SheetTitle>Tenant onboarded</SheetTitle>
              <SheetDescription>{result.tenant.displayName} · {result.tenant.schema} · {result.tenant.subdomain}</SheetDescription>
            </SheetHeader>
            <div className="mt-4">
              <CredentialReveal
                title="Tenant onboarded"
                subtitle={`${result.tenant.displayName} · ${result.tenant.schema}`}
                identityLabel="First admin"
                identityValue={`${result.firstAdmin.username} · ${result.firstAdmin.email}`}
                secret={result.firstAdmin.temporaryPassword}
                onDone={close}
              />
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
