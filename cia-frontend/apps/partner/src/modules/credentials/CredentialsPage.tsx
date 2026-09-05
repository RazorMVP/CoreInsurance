import { useState } from 'react';
import { useCredentials, useRotateSecret } from '@cia/api-client';
import { useSelectedApp } from '../../app/AppContext';
import { SelectAppNotice } from '../../app/SelectAppNotice';
import { copyToClipboard } from '../../lib/copy';

export default function CredentialsPage() {
  const { selectedAppId, selectedApp } = useSelectedApp();
  const appId = selectedAppId ?? '';
  const credsQuery = useCredentials(appId);
  const rotate = useRotateSecret(appId);
  const [revealed, setRevealed] = useState<string | null>(null);
  const canRotate = selectedApp?.role === 'MANAGER';

  if (!selectedAppId) {
    return (
      <div className="max-w-2xl space-y-6">
        <h1 className="text-xl font-semibold text-foreground">Credentials</h1>
        <SelectAppNotice />
      </div>
    );
  }

  const onRotate = () => {
    rotate.reset();
    rotate.mutate(undefined, { onSuccess: (r) => setRevealed(r.clientSecret) });
  };
  const dismiss = () => { setRevealed(null); rotate.reset(); };

  return (
    <div className="max-w-2xl space-y-6">
      <h1 className="text-xl font-semibold text-foreground">Credentials</h1>
      {credsQuery.isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {credsQuery.data && (
        <div className="rounded-lg border border-border bg-card p-5">
          <dl className="space-y-3 text-sm">
            <div>
              <dt className="text-muted-foreground">Client ID</dt>
              <dd className="mt-1 font-mono text-foreground">{credsQuery.data.clientId}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Granted scopes</dt>
              <dd className="mt-1 flex flex-wrap gap-1">
                {credsQuery.data.scopes.map((s) => (
                  <span key={s} className="rounded bg-muted px-2 py-0.5 font-mono text-xs text-foreground">{s}</span>
                ))}
              </dd>
            </div>
          </dl>
        </div>
      )}

      {revealed ? (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-5">
          <p className="text-sm font-medium text-amber-300">New client secret — shown once</p>
          <p className="mt-2 break-all font-mono text-sm text-foreground">{revealed}</p>
          <div className="mt-3 flex gap-2">
            <button onClick={() => copyToClipboard(revealed)} className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground">Copy</button>
            <button onClick={dismiss} className="rounded-md border border-border px-3 py-1.5 text-sm text-foreground">Dismiss</button>
          </div>
        </div>
      ) : (
        <button
          onClick={onRotate}
          disabled={!canRotate || rotate.isPending}
          title={canRotate ? undefined : 'Only a MANAGER can rotate the secret'}
          className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-50"
        >
          {rotate.isPending ? 'Rotating…' : 'Rotate secret'}
        </button>
      )}
      {rotate.isError && <p className="text-sm text-red-400">Could not rotate the secret. Try again.</p>}
    </div>
  );
}
