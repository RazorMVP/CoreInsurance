import { useState } from 'react';
import {
  Badge,
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton,
  cn,
  toast,
} from '@cia/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, unwrapPageData, type SpringPageResponse } from '@cia/api-client';
import type { TemplateRow, TemplateType } from './template-types';
import { TEMPLATE_TYPES } from './template-types';
import TemplateUploadSheet from './TemplateUploadSheet';

interface ProductRow {
  id: string;
  name: string;
  classOfBusinessId?: string | null;
}

interface DocumentTemplateResponse {
  id: string;
  templateType: TemplateType;
  productId?: string | null;
  classOfBusinessId?: string | null;
  storagePath: string;
  description?: string | null;
  active: boolean;
  createdAt: string;
}

const TYPE_BADGE: Record<TemplateType, string> = {
  POLICY:             'bg-blue-50 text-blue-700 border-blue-200',
  ENDORSEMENT:        'bg-teal-50 text-teal-700 border-teal-200',
  CLAIM_DV:           'bg-purple-50 text-purple-700 border-purple-200',
  NAICOM_CERTIFICATE: 'bg-amber-50 text-amber-700 border-amber-200',
};

function typeLabelOf(type: TemplateType): string {
  return TEMPLATE_TYPES.find(t => t.value === type)?.label ?? type;
}

function fileNameOf(storagePath: string): string {
  return storagePath.split('/').pop() ?? storagePath;
}

function toTemplateRow(t: DocumentTemplateResponse, products: ProductRow[]): TemplateRow {
  const product = products.find((p) => p.id === t.productId);
  return {
    id:                t.id,
    productId:         t.productId,
    classOfBusinessId: t.classOfBusinessId,
    productName:       product?.name,
    storagePath:       t.storagePath,
    description:       t.description,
    type:              t.templateType,
    status:            t.active ? 'ACTIVE' : 'ARCHIVED',
    uploadedAt:        t.createdAt.slice(0, 10),
  };
}

export default function TemplatesTab() {
  const queryClient = useQueryClient();
  const [selectedProd, setSelectedProd] = useState('');
  const [uploadOpen, setUploadOpen] = useState(false);
  const [replaceTarget, setReplaceTarget] = useState<Pick<TemplateRow, 'id' | 'type'> | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);

  const productsQuery = useQuery<ProductRow[]>({
    queryKey: ['setup', 'products', 'template-picker'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: SpringPageResponse<ProductRow> | ProductRow[] }>(
        '/api/v1/setup/products',
        { params: { size: 100 } },
      );
      return unwrapPageData(res.data.data);
    },
  });

  const templatesQuery = useQuery<TemplateRow[]>({
    queryKey: ['setup', 'document-templates'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: SpringPageResponse<DocumentTemplateResponse> | DocumentTemplateResponse[] }>(
        '/api/v1/document-templates',
        { params: { size: 200 } },
      );
      const products = productsQuery.data ?? [];
      return unwrapPageData(res.data.data).map((template) => toTemplateRow(template, products));
    },
  });

  const uploadTemplate = useMutation({
    mutationFn: async (values: { description?: string; type: TemplateType; file: File }) => {
      const product = productsQuery.data?.find((p) => p.id === selectedProd);
      const formData = new FormData();
      formData.append('templateType', values.type);
      formData.append('productId', selectedProd);
      if (product?.classOfBusinessId) formData.append('classOfBusinessId', product.classOfBusinessId);
      if (values.description?.trim()) formData.append('description', values.description.trim());
      formData.append('file', values.file);

      const res = await apiClient.post<{ data: DocumentTemplateResponse }>(
        '/api/v1/document-templates',
        formData,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'document-templates'] });
      setReplaceTarget(null);
      toast({ title: 'Template uploaded' });
    },
    onError: () => {
      toast({ variant: 'destructive', title: 'Template upload failed' });
    },
  });

  const deleteTemplate = useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/api/v1/document-templates/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'document-templates'] });
      setDeleteId(null);
      toast({ title: 'Template deleted' });
    },
    onError: () => {
      toast({ variant: 'destructive', title: 'Template delete failed' });
    },
  });

  const products = productsQuery.data ?? [];
  const templates = templatesQuery.data ?? [];
  const product = products.find(p => p.id === selectedProd);
  const visible = templates.filter(t => t.productId === selectedProd);
  const activeCount = visible.filter(t => t.status === 'ACTIVE').length;
  const deleteTarget = templates.find(t => t.id === deleteId);

  return (
    <>
      <div className="flex items-center gap-3 flex-wrap mb-5">
        <span className="text-sm text-muted-foreground shrink-0">Product</span>
        <Select value={selectedProd} onValueChange={setSelectedProd} disabled={productsQuery.isLoading}>
          <SelectTrigger className="w-[260px]">
            <SelectValue placeholder={productsQuery.isLoading ? 'Loading products...' : 'Select a product...'} />
          </SelectTrigger>
          <SelectContent>
            {products.map(p => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}
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
            Upload Template
          </Button>
        )}
      </div>

      {productsQuery.isError ? (
        <EmptyState
          title="Products unavailable"
          description="Products are required before templates can be scoped and uploaded."
          action={<Button size="sm" variant="outline" onClick={() => productsQuery.refetch()}>Retry</Button>}
        />
      ) : productsQuery.isLoading || templatesQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-14 w-full" /></div>
      ) : !selectedProd ? (
        <EmptyState title="Select a product to view its templates" />
      ) : templatesQuery.isError ? (
        <EmptyState
          title="Templates unavailable"
          description="The document template service did not return a usable response."
          action={<Button size="sm" variant="outline" onClick={() => templatesQuery.refetch()}>Retry</Button>}
        />
      ) : visible.length === 0 ? (
        <EmptyState
          title="No templates yet"
          action={<Button size="sm" onClick={() => setUploadOpen(true)}>Upload Template</Button>}
        />
      ) : (
        <div className="space-y-2">
          <div className="grid grid-cols-[2fr_1.5fr_1fr_1fr_auto] gap-3 px-3 py-1.5 rounded-md bg-muted text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
            <span>Template</span><span>Type</span><span>Uploaded</span><span>Status</span><span></span>
          </div>

          {visible.map(t => (
            <div
              key={t.id}
              className={cn(
                'grid grid-cols-[2fr_1.5fr_1fr_1fr_auto] gap-3 items-center rounded-lg border px-3 py-3',
                t.status === 'ARCHIVED' && 'opacity-60',
              )}
            >
              <div>
                <p className="text-sm font-medium text-foreground">{t.description || typeLabelOf(t.type)}</p>
                <p className="font-mono text-[10px] text-muted-foreground mt-0.5">{fileNameOf(t.storagePath)}</p>
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
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm" className="h-7 w-7 p-0 text-muted-foreground">...</Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem onClick={() => { setReplaceTarget({ id: t.id, type: t.type }); setUploadOpen(true); }}>
                    Replace
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    className="text-destructive focus:text-destructive"
                    onClick={() => setDeleteId(t.id)}
                  >
                    Delete
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          ))}
        </div>
      )}

      <TemplateUploadSheet
        open={uploadOpen}
        onOpenChange={(v) => { setUploadOpen(v); if (!v) setReplaceTarget(null); }}
        productId={selectedProd}
        productName={product?.name ?? ''}
        replaceTemplate={replaceTarget}
        onSave={(values) => uploadTemplate.mutateAsync(values).then(() => undefined)}
      />

      <Dialog open={!!deleteId} onOpenChange={() => setDeleteId(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete template?</DialogTitle>
            <DialogDescription>
              {deleteTarget?.status === 'ACTIVE'
                ? 'This template is active and may be used for new document generation. This cannot be undone.'
                : 'This cannot be undone.'}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteId(null)} disabled={deleteTemplate.isPending}>Cancel</Button>
            <Button
              variant="destructive"
              onClick={() => deleteId && deleteTemplate.mutate(deleteId)}
              disabled={deleteTemplate.isPending}
            >
              {deleteTemplate.isPending ? 'Deleting...' : 'Delete'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
