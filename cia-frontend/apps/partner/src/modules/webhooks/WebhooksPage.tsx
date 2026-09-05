import { useState } from 'react';
import { useWebhooks, useCreateWebhook, useDeleteWebhook, useUsage } from '@cia/api-client';
import { useSelectedApp } from '../../app/AppContext';
import { formatInt, formatTimestamp } from '../../lib/format';

const EVENTS = ['policy.bound', 'policy.endorsed', 'policy.cancelled', 'claim.registered', 'claim.approved', 'claim.settled', 'quote.created', 'quote.expired', 'kyc.completed', 'renewal.due'];

export default function WebhooksPage() {
  const { selectedAppId, selectedApp } = useSelectedApp();
  const appId = selectedAppId ?? '';
  const webhooksQuery = useWebhooks(appId);
  const usageQuery = useUsage(appId);
  const create = useCreateWebhook(appId);
  const del = useDeleteWebhook(appId);
  const canManage = selectedApp?.role === 'MANAGER';

  const [targetUrl, setTargetUrl] = useState('');
  const [secret, setSecret] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const toggle = (ev: string) => setSelected((s) => (s.includes(ev) ? s.filter((e) => e !== ev) : [...s, ev]));

  const submit = () => {
    if (!targetUrl.trim()) { setError('Target URL is required'); return; }
    if (secret.length < 16) { setError('Signing secret must be at least 16 characters'); return; }
    if (selected.length === 0) { setError('Select at least one event type'); return; }
    setError(null);
    create.mutate({ targetUrl, secret, eventTypes: selected }, {
      onSuccess: () => { setTargetUrl(''); setSecret(''); setSelected([]); },
    });
  };

  const wd = usageQuery.data?.webhookDeliveries;

  return (
    <div className="max-w-3xl space-y-6">
      <h1 className="text-xl font-semibold text-foreground">Webhooks</h1>

      {canManage && (
        <div className="rounded-lg border border-border bg-card p-5 space-y-3">
          <div>
            <label htmlFor="wh-url" className="text-sm text-muted-foreground">Target URL</label>
            <input id="wh-url" value={targetUrl} onChange={(e) => setTargetUrl(e.target.value)} className="mt-1 w-full rounded-md border border-border bg-background px-2 py-2 text-sm text-foreground" placeholder="https://…" />
          </div>
          <div>
            <label htmlFor="wh-secret" className="text-sm text-muted-foreground">Signing secret (min 16 chars)</label>
            <input id="wh-secret" type="password" value={secret} onChange={(e) => setSecret(e.target.value)} className="mt-1 w-full rounded-md border border-border bg-background px-2 py-2 font-mono text-sm text-foreground" />
          </div>
          <fieldset className="flex flex-wrap gap-2">
            {EVENTS.map((ev) => (
              <label key={ev} className="flex items-center gap-1 rounded border border-border px-2 py-1 text-xs text-foreground">
                <input type="checkbox" aria-label={ev} checked={selected.includes(ev)} onChange={() => toggle(ev)} />
                {ev}
              </label>
            ))}
          </fieldset>
          {error && <p className="text-sm text-red-400">{error}</p>}
          {create.isError && <p className="text-sm text-red-400">Could not register the webhook. Try again.</p>}
          <button onClick={submit} disabled={create.isPending} className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-50">
            {create.isPending ? 'Registering…' : 'Register webhook'}
          </button>
        </div>
      )}

      <div className="rounded-lg border border-border bg-card">
        {webhooksQuery.isLoading && <p className="p-4 text-sm text-muted-foreground">Loading…</p>}
        {webhooksQuery.data?.length === 0 && <p className="p-4 text-sm text-muted-foreground">No webhooks registered.</p>}
        <ul className="divide-y divide-border">
          {webhooksQuery.data?.map((w) => (
            <li key={w.id} className="flex items-center justify-between p-4">
              <div>
                <p className="font-mono text-sm text-foreground">{w.targetUrl}</p>
                <p className="mt-1 flex flex-wrap gap-1">
                  {w.eventTypes.map((e) => <span key={e} className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">{e}</span>)}
                </p>
              </div>
              <div className="flex items-center gap-3">
                <span className={`text-xs ${w.active ? 'text-emerald-400' : 'text-muted-foreground'}`}>{w.active ? 'Active' : 'Inactive'}</span>
                {canManage && <button onClick={() => del.mutate(w.id)} disabled={del.isPending} className="text-xs text-red-400 hover:underline disabled:opacity-50">Delete</button>}
              </div>
            </li>
          ))}
        </ul>
      </div>

      <div className="rounded-lg border border-border bg-card p-5">
        <h2 className="text-sm font-medium text-foreground">Delivery summary</h2>
        <dl className="mt-3 grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
          <div><dt className="text-muted-foreground">Total</dt><dd className="text-foreground">{formatInt(wd?.totalDeliveries)}</dd></div>
          <div><dt className="text-muted-foreground">Succeeded</dt><dd className="text-emerald-400">{formatInt(wd?.successfulDeliveries)}</dd></div>
          <div><dt className="text-muted-foreground">Failed</dt><dd className="text-red-400">{formatInt(wd?.failedDeliveries)}</dd></div>
          <div><dt className="text-muted-foreground">Last delivery</dt><dd className="text-foreground">{formatTimestamp(wd?.lastDeliveryAt)}</dd></div>
        </dl>
      </div>
    </div>
  );
}
