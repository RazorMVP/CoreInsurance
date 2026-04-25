import { useState } from 'react';
import {
  Badge,
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  cn,
} from '@cia/ui';
import type { TemplateRow, TemplateType } from './template-types';
import { TEMPLATE_TYPES } from './template-types';
import TemplateUploadSheet from './TemplateUploadSheet';

// ── Products (same list as clause-types.ts — kept local for tab independence) ─
const PRODUCTS = [
  { id: '1', name: 'Private Motor Comprehensive' },
  { id: '2', name: 'Commercial Vehicle' },
  { id: '3', name: 'Fire & Burglary Standard' },
  { id: '4', name: 'Marine Cargo Open Cover' },
];

// ── Mock data ─────────────────────────────────────────────────────────────────
const INITIAL_TEMPLATES: TemplateRow[] = [
  { id: 't1', productId: '1', productName: 'Private Motor Comprehensive', name: 'Motor Comprehensive Policy v3', filename: 'policy_template_pmc_v3.docx',  type: 'POLICY_DOCUMENT', status: 'ACTIVE',   uploadedAt: '2026-03-15' },
  { id: 't2', productId: '1', productName: 'Private Motor Comprehensive', name: 'NAICOM Motor Certificate',      filename: 'naicom_cert_motor_v2.docx',      type: 'CERTIFICATE',     status: 'ACTIVE',   uploadedAt: '2026-01-10' },
  { id: 't3', productId: '1', productName: 'Private Motor Comprehensive', name: 'Policy Schedule',               filename: 'policy_schedule_pmc.docx',       type: 'SCHEDULE',        status: 'ARCHIVED', uploadedAt: '2025-11-02' },
];

// ── Badge styles per template type ───────────────────────────────────────────
const TYPE_BADGE: Record<TemplateType, string> = {
  POLICY_DOCUMENT: 'bg-blue-50 text-blue-700 border-blue-200',
  CERTIFICATE:     'bg-amber-50 text-amber-700 border-amber-200',
  SCHEDULE:        'bg-neutral-100 text-neutral-600 border-neutral-200',
  DEBIT_NOTE:      'bg-purple-50 text-purple-700 border-purple-200',
  ENDORSEMENT:     'bg-teal-50 text-teal-700 border-teal-200',
  OTHER:           'bg-neutral-100 text-neutral-600 border-neutral-200',
};

function typeLabelOf(type: TemplateType): string {
  return TEMPLATE_TYPES.find(t => t.value === type)?.label ?? type;
}

// ── Component ─────────────────────────────────────────────────────────────────
export default function TemplatesTab() {
  const [templates,     setTemplates]     = useState<TemplateRow[]>(INITIAL_TEMPLATES);
  const [selectedProd,  setSelectedProd]  = useState('');
  const [uploadOpen,    setUploadOpen]    = useState(false);
  const [replaceTarget, setReplaceTarget] = useState<Pick<TemplateRow, 'id' | 'type'> | null>(null);
  const [archiveId,     setArchiveId]     = useState<string | null>(null);
  const [deleteId,      setDeleteId]      = useState<string | null>(null);

  const product     = PRODUCTS.find(p => p.id === selectedProd);
  const visible     = templates.filter(t => t.productId === selectedProd);
  const activeCount = visible.filter(t => t.status === 'ACTIVE').length;

  function handleUpload(values: { name: string; type: TemplateType; file: File; replaceId?: string }) {
    const prod = product!;
    if (values.replaceId) {
      setTemplates(prev => prev.map(t =>
        t.id === values.replaceId ? { ...t, status: 'ARCHIVED' as const } : t,
      ));
    }
    setTemplates(prev => [
      ...prev,
      {
        id: crypto.randomUUID(),
        productId:   prod.id,
        productName: prod.name,
        name:        values.name,
        filename:    values.file.name,
        type:        values.type,
        status:      'ACTIVE',
        uploadedAt:  new Date().toISOString().slice(0, 10),
      },
    ]);
    setReplaceTarget(null);
  }

  function handleArchive() {
    if (archiveId) {
      setTemplates(prev => prev.map(t => t.id === archiveId ? { ...t, status: 'ARCHIVED' } : t));
      setArchiveId(null);
    }
  }

  function handleDelete() {
    if (deleteId) {
      setTemplates(prev => prev.filter(t => t.id !== deleteId));
      setDeleteId(null);
    }
  }

  const deleteTarget = templates.find(t => t.id === deleteId);

  return (
    <>
      {/* Product selector row */}
      <div className="flex items-center gap-3 flex-wrap mb-5">
        <span className="text-sm text-muted-foreground shrink-0">Product</span>
        <Select value={selectedProd} onValueChange={setSelectedProd}>
          <SelectTrigger className="w-[260px]">
            <SelectValue placeholder="Select a product…" />
          </SelectTrigger>
          <SelectContent>
            {PRODUCTS.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}
          </SelectContent>
        </Select>
        {selectedProd && (
          <span className="text-xs text-muted-foreground">
            {activeCount} active template{activeCount !== 1 ? 's' : ''}
          </span>
        )}
        <div className="flex-1" />
        {selectedProd && (
          <Button size="sm" onClick={() => { setReplaceTarget(null); setUploadOpen(true); }}>
            ↑ Upload Template
          </Button>
        )}
      </div>

      {/* Content */}
      {!selectedProd ? (
        <EmptyState title="Select a product to view its templates" />
      ) : visible.length === 0 ? (
        <EmptyState
          title="No templates yet"
          action={<Button size="sm" onClick={() => setUploadOpen(true)}>Upload Template</Button>}
        />
      ) : (
        <div className="space-y-2">
          {/* List header */}
          <div className="grid grid-cols-[2fr_1.5fr_1fr_1fr_auto] gap-3 px-3 py-1.5 rounded-md bg-muted text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
            <span>Template</span><span>Type</span><span>Uploaded</span><span>Status</span><span></span>
          </div>

          {/* Template rows */}
          {visible.map(t => (
            <div
              key={t.id}
              className={cn(
                'grid grid-cols-[2fr_1.5fr_1fr_1fr_auto] gap-3 items-center rounded-lg border px-3 py-3',
                t.status === 'ARCHIVED' && 'opacity-60',
              )}
            >
              <div>
                <p className="text-sm font-medium text-foreground">{t.name}</p>
                <p className="font-mono text-[10px] text-muted-foreground mt-0.5">{t.filename}</p>
              </div>
              <Badge className={cn('text-[10px] border w-fit hover:opacity-100', TYPE_BADGE[t.type])}>
                {typeLabelOf(t.type)}
              </Badge>
              <span className="text-sm text-foreground">{t.uploadedAt}</span>
              <Badge
                className={cn(
                  'text-[10px] border w-fit hover:opacity-100',
                  t.status === 'ACTIVE'
                    ? 'bg-green-50 text-green-700 border-green-200'
                    : 'bg-neutral-100 text-neutral-500 border-neutral-200',
                )}
              >
                {t.status === 'ACTIVE' ? 'Active' : 'Archived'}
              </Badge>
              <div className="flex items-center gap-1">
                {/* Download (mock) */}
                <Button variant="ghost" size="sm" className="h-7 w-7 p-0 text-primary" title="Download">↓</Button>
                {/* Actions */}
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="sm" className="h-7 w-7 p-0 text-muted-foreground">⋯</Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem onClick={() => { setReplaceTarget({ id: t.id, type: t.type }); setUploadOpen(true); }}>
                      Replace
                    </DropdownMenuItem>
                    {t.status === 'ACTIVE' && (
                      <DropdownMenuItem onClick={() => setArchiveId(t.id)}>Archive</DropdownMenuItem>
                    )}
                    <DropdownMenuItem
                      className="text-destructive focus:text-destructive"
                      onClick={() => setDeleteId(t.id)}
                    >
                      Delete
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Upload / Replace sheet */}
      <TemplateUploadSheet
        open={uploadOpen}
        onOpenChange={(v) => { setUploadOpen(v); if (!v) setReplaceTarget(null); }}
        productId={selectedProd}
        productName={product?.name ?? ''}
        replaceTemplate={replaceTarget}
        onSave={handleUpload}
      />

      {/* Archive confirm */}
      <Dialog open={!!archiveId} onOpenChange={() => setArchiveId(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Archive template?</DialogTitle>
            <DialogDescription>The template will be marked as archived and will no longer be used for new documents.</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setArchiveId(null)}>Cancel</Button>
            <Button onClick={handleArchive}>Archive</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete confirm */}
      <Dialog open={!!deleteId} onOpenChange={() => setDeleteId(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete template?</DialogTitle>
            <DialogDescription>
              {deleteTarget?.status === 'ACTIVE'
                ? 'Warning: this template is currently active and may be in use. This cannot be undone.'
                : 'This cannot be undone.'}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteId(null)}>Cancel</Button>
            <Button variant="destructive" onClick={handleDelete}>Delete</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
