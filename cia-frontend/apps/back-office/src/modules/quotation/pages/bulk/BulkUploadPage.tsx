import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, Button, Card, CardContent, PageHeader } from '@cia/ui';

type UploadState = 'idle' | 'validating' | 'done';

export default function BulkUploadPage() {
  const navigate = useNavigate();
  const [state, setState] = useState<UploadState>('idle');
  const [dragOver, setDragOver] = useState(false);

  function handleDrop(e: React.DragEvent) {
    e.preventDefault(); setDragOver(false); setState('validating');
    setTimeout(() => setState('done'), 1200);
  }

  return (
    <div className="p-6 space-y-5 max-w-3xl">
      <PageHeader
        title="Bulk Quote Upload"
        description="Upload a CSV file to create multiple quotes in one operation."
        breadcrumb={<button onClick={() => navigate('/quotation')} className="text-sm text-muted-foreground hover:text-foreground">← Quotation</button>}
      />

      {/* Drop zone */}
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
            <p className="text-sm font-medium text-foreground">Drag and drop your CSV file here</p>
            <p className="mt-1 text-xs text-muted-foreground">or <button className="text-primary hover:underline" onClick={() => setState('done')}>browse files</button></p>
            <p className="mt-3 text-xs text-muted-foreground">Supports: CSV files up to 5 MB · Max 500 quotes per upload</p>
          </>
        )}
        {state === 'validating' && (
          <p className="text-sm font-medium text-foreground animate-pulse">Validating file…</p>
        )}
        {state === 'done' && (
          <div className="space-y-1">
            <p className="text-sm font-medium text-foreground">File validated</p>
            <p className="text-xs text-muted-foreground">12 quotes ready to create · 1 row with errors</p>
          </div>
        )}
      </div>

      {/* Validation results */}
      {state === 'done' && (
        <Card>
          <CardContent className="p-0">
            <div className="flex items-center justify-between px-5 py-3" style={{ boxShadow: '0 1px 0 var(--border)' }}>
              <p className="text-sm font-semibold text-foreground">Validation Results</p>
              <div className="flex gap-2">
                <Badge variant="active">12 valid</Badge>
                <Badge variant="rejected">1 error</Badge>
              </div>
            </div>
            <div className="px-5 py-3 text-sm">
              <p className="text-destructive font-medium">Row 8 — Missing sum insured value</p>
              <p className="text-xs text-muted-foreground mt-0.5">Fix the CSV and re-upload, or skip this row and proceed with 12 quotes.</p>
            </div>
            <div className="flex justify-end gap-2 px-5 py-3" style={{ boxShadow: '1px 0 0 var(--border), inset 0 1px 0 var(--border)' }}>
              <Button variant="outline" onClick={() => setState('idle')}>Re-upload</Button>
              <Button onClick={() => navigate('/quotation')}>Create 12 Quotes</Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* CSV template */}
      <Card>
        <CardContent className="p-5">
          <p className="text-sm font-semibold text-foreground mb-2">CSV Template</p>
          <p className="text-xs text-muted-foreground mb-3">Download the template and fill in your quote data. Required columns are marked *.</p>
          <code className="block rounded bg-muted px-3 py-2 text-xs font-mono text-muted-foreground">
            customer_id*, product_id*, start_date*, end_date*, sum_insured*, rate*, discount, risk_description*
          </code>
          <Button variant="outline" size="sm" className="mt-3">Download Template</Button>
        </CardContent>
      </Card>
    </div>
  );
}
