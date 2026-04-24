import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, Button, Card, CardContent, PageHeader } from '@cia/ui';

type State = 'idle' | 'validating' | 'done';

export default function BulkClaimPage() {
  const navigate = useNavigate();
  const [state, setState] = useState<State>('idle');
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  function processFile() {
    setState('validating');
    setTimeout(() => setState('done'), 1200);
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault(); setDragOver(false);
    if (e.dataTransfer.files?.[0]) processFile();
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    if (e.target.files?.[0]) processFile();
  }

  return (
    <div className="p-6 space-y-5 max-w-3xl">
      <PageHeader
        title="Bulk Claim Registration"
        description="Upload a CSV to register multiple claims at once."
        breadcrumb={<button onClick={() => navigate('/claims')} className="text-sm text-muted-foreground hover:text-foreground">← Claims</button>}
      />

      <div
        className={`rounded-lg border-2 border-dashed p-12 text-center transition-colors ${dragOver ? 'border-primary bg-teal-50' : 'border-border bg-card'}`}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
      >
        {state === 'idle' && (
          <>
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-muted">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" className="text-muted-foreground">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
              </svg>
            </div>
            <p className="text-sm font-medium text-foreground">
              Drop CSV here or{' '}
              <button
                type="button"
                className="text-primary hover:underline"
                onClick={() => fileInputRef.current?.click()}
              >
                browse
              </button>
            </p>
            <p className="mt-1 text-xs text-muted-foreground">Max 200 claims per upload · CSV only</p>
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv"
              className="sr-only"
              onChange={handleFileChange}
            />
          </>
        )}
        {state === 'validating' && <p className="text-sm font-medium animate-pulse">Validating…</p>}
        {state === 'done' && <p className="text-sm font-medium text-foreground">8 claims ready · 1 error</p>}
      </div>

      {state === 'done' && (
        <Card>
          <CardContent className="p-0">
            <div className="flex items-center justify-between px-5 py-3" style={{ boxShadow: '0 1px 0 var(--border)' }}>
              <p className="text-sm font-semibold">Validation Results</p>
              <div className="flex gap-2"><Badge variant="active">8 valid</Badge><Badge variant="rejected">1 error</Badge></div>
            </div>
            <div className="px-5 py-3">
              <p className="text-sm text-destructive font-medium">Row 5 — Policy POL-2026-00099 not found</p>
              <p className="text-xs text-muted-foreground mt-0.5">Fix the CSV or skip this row and proceed with 8 claims.</p>
            </div>
            <div className="flex justify-end gap-2 px-5 py-3" style={{ boxShadow: 'inset 0 1px 0 var(--border)' }}>
              <Button variant="outline" onClick={() => setState('idle')}>Re-upload</Button>
              <Button onClick={() => navigate('/claims')}>Register 8 Claims</Button>
            </div>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardContent className="p-5">
          <p className="text-sm font-semibold mb-2">CSV Template</p>
          <code className="block rounded bg-muted px-3 py-2 text-xs font-mono text-muted-foreground">
            policy_number*, incident_date*, nature_of_loss*, cause_of_loss*, description*, location*, estimated_loss*, contact_name, contact_phone
          </code>
          <Button variant="outline" size="sm" className="mt-3">Download Template</Button>
        </CardContent>
      </Card>
    </div>
  );
}
