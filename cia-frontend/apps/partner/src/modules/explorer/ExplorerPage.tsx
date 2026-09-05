import { useState } from 'react';
import { useTryIt, type TryItResult } from '@cia/api-client';
import { useSelectedApp } from '../../app/AppContext';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
const QUICK = [
  { label: 'GET products', method: 'GET', path: 'products' },
  { label: 'GET policies', method: 'GET', path: 'policies' },
  { label: 'POST quotes', method: 'POST', path: 'quotes' },
];

function statusClass(s: number) {
  if (s >= 200 && s < 300) return 'text-emerald-400';
  if (s === 429) return 'text-amber-400';
  return 'text-red-400';
}

export default function ExplorerPage() {
  const { selectedAppId } = useSelectedApp();
  const appId = selectedAppId ?? '';
  const tryIt = useTryIt(appId);
  const [method, setMethod] = useState('GET');
  const [path, setPath] = useState('products');
  const [body, setBody] = useState('');
  const [result, setResult] = useState<TryItResult | null>(null);
  const [bodyError, setBodyError] = useState<string | null>(null);

  const send = () => {
    let parsed: unknown;
    if (method !== 'GET' && body.trim()) {
      try { parsed = JSON.parse(body); }
      catch { setBodyError('Body is not valid JSON'); return; }
    }
    setBodyError(null);
    tryIt.mutate({ method, path, body: parsed }, { onSuccess: setResult });
  };

  return (
    <div className="max-w-3xl space-y-5">
      <h1 className="text-xl font-semibold text-foreground">API Explorer</h1>
      <p className="text-sm text-muted-foreground">Calls run against <code className="font-mono">/partner/v1/</code> exactly as a real integration — scope and rate-limit errors are shown verbatim.</p>

      <div className="flex flex-wrap gap-2">
        {QUICK.map((q) => (
          <button key={q.label} onClick={() => { setMethod(q.method); setPath(q.path); }} className="rounded-md border border-border px-2 py-1 text-xs text-muted-foreground hover:text-foreground">{q.label}</button>
        ))}
      </div>

      <div className="rounded-lg border border-border bg-card p-4">
        <div className="flex items-center gap-2">
          <label className="sr-only" htmlFor="method">Method</label>
          <select id="method" value={method} onChange={(e) => setMethod(e.target.value)} className="rounded-md border border-border bg-background px-2 py-2 text-sm text-foreground">
            {METHODS.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <span className="font-mono text-sm text-muted-foreground">/partner/v1/</span>
          <label className="sr-only" htmlFor="path">Path</label>
          <input id="path" value={path} onChange={(e) => setPath(e.target.value)} className="flex-1 rounded-md border border-border bg-background px-2 py-2 font-mono text-sm text-foreground" placeholder="products" />
          <button onClick={send} disabled={!appId || tryIt.isPending} className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-50">
            {tryIt.isPending ? 'Sending…' : 'Send'}
          </button>
        </div>
        {method !== 'GET' && (
          <textarea value={body} onChange={(e) => setBody(e.target.value)} rows={5} className="mt-3 w-full rounded-md border border-border bg-background p-2 font-mono text-xs text-foreground" placeholder='{ "productId": "…" }' />
        )}
        {bodyError && <p className="mt-1 text-xs text-red-400">{bodyError}</p>}
      </div>

      {result && (
        <div className="rounded-lg border border-border bg-card p-4">
          <p className="text-sm">Status: <span className={`font-mono font-semibold ${statusClass(result.status)}`}>{result.status}</span></p>
          <pre className="mt-2 max-h-96 overflow-auto rounded bg-background p-3 text-xs text-foreground">{JSON.stringify(result.body, null, 2)}</pre>
        </div>
      )}
      {tryIt.isError && <p className="text-sm text-red-400">The portal could not reach the API. Try again.</p>}
    </div>
  );
}
