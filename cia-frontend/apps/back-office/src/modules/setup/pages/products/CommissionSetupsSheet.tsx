import { zodResolver } from '@hookform/resolvers/zod';
import {
  Badge,
  Button,
  DataTable, DataTableColumnHeader, DataTableRowActions,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  EmptyState,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
  Skeleton,
} from '@cia/ui';
import {
  apiClient,
  type CommissionSetupDto,
  type CommissionSourceType,
  type ProductDto,
} from '@cia/api-client';
import { type ColumnDef } from '@tanstack/react-table';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';
import { useDeleteWithReason } from '@/lib/use-delete-with-reason';

// ── Source labels for UI display ──────────────────────────────────────────
const SOURCE_LABEL: Record<CommissionSourceType, string> = {
  AGENT:                'Agent',
  BROKER:               'Broker',
  RELATIONSHIP_MANAGER: 'Relationship Manager',
};

// ── Form schema ────────────────────────────────────────────────────────────
// Mirrors com.nubeero.cia.setup.product.dto.CommissionSetupRequest (Session 84 / V50).
const schema = z
  .object({
    commissionSource: z.enum(['AGENT', 'BROKER', 'RELATIONSHIP_MANAGER']),
    rate:             z.coerce.number().min(0, 'Required').max(100, 'Max 100%'),
    effectiveFrom:    z.string().min(1, 'Required'),
    effectiveTo:      z.string().optional().or(z.literal('')),
  })
  .refine(
    (v) => !v.effectiveTo || v.effectiveTo >= v.effectiveFrom,
    { message: 'End date must be on or after start date', path: ['effectiveTo'] },
  );
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  product: ProductDto | null;
}

export default function CommissionSetupsSheet({ open, onOpenChange, product }: Props) {
  const queryClient = useQueryClient();

  const [editorOpen, setEditorOpen]   = useState(false);
  const [editing, setEditing]         = useState<CommissionSetupDto | null>(null);

  const setupsQuery = useQuery<CommissionSetupDto[]>({
    queryKey: ['setup', 'commission-setups', product?.id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CommissionSetupDto[] }>(
        `/api/v1/setup/products/${product!.id}/commission-setups`,
      );
      return res.data.data;
    },
    enabled: open && !!product,
  });
  const setups = setupsQuery.data ?? [];

  const { setTarget, dialog: deleteDialog } = useDeleteWithReason<CommissionSetupDto>({
    endpoint:      (id) => `/api/v1/setup/products/${product?.id}/commission-setups/${id}`,
    invalidateKey: ['setup', 'commission-setups', product?.id],
    entityLabel:   'commission setup',
    entityName:    (cs) => `${SOURCE_LABEL[cs.commissionSource]} @ ${cs.rate}%`,
  });

  function openAdd()                              { setEditing(null);  setEditorOpen(true); }
  function openEdit(cs: CommissionSetupDto)       { setEditing(cs);    setEditorOpen(true); }

  const columns: ColumnDef<CommissionSetupDto>[] = [
    {
      accessorKey: 'commissionSource',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Source" />,
      cell: ({ getValue }) => {
        const s = getValue() as CommissionSourceType;
        return <Badge variant="outline">{SOURCE_LABEL[s]}</Badge>;
      },
    },
    {
      accessorKey: 'rate',
      header: 'Rate',
      cell: ({ getValue }) => <span className="text-sm font-mono">{getValue() as number}%</span>,
    },
    {
      accessorKey: 'effectiveFrom',
      header: 'Effective From',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span>,
    },
    {
      accessorKey: 'effectiveTo',
      header: 'Effective To',
      cell: ({ getValue }) => {
        const to = getValue() as string | null | undefined;
        return <span className="text-sm text-muted-foreground">{to ?? '—'}</span>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            { label: 'Edit',   onClick: (r) => openEdit(r.original) },
            { label: 'Delete', onClick: (r) => setTarget(r.original) },
          ]}
        />
      ),
    },
  ];

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-2xl overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Commission Setup{product ? ` — ${product.name}` : ''}</SheetTitle>
          <SheetDescription>
            Per-source commission rules for this product. Each rule applies during its effective window.
            At policy issuance the active rule for the configured source is snapshotted onto the policy
            so later rate changes don&apos;t flow into already-issued contracts.
          </SheetDescription>
        </SheetHeader>

        <div className="mt-6 space-y-4">
          <div className="flex items-center justify-end">
            <Button onClick={openAdd} disabled={!product}>Add Rule</Button>
          </div>

          {setupsQuery.isLoading ? (
            <div className="space-y-3">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          ) : setups.length === 0 ? (
            <EmptyState
              title="No commission rules"
              description="Add a rule to start tracking commission payable per source."
              action={<Button onClick={openAdd} disabled={!product}>Add Rule</Button>}
            />
          ) : (
            <DataTable columns={columns} data={setups} />
          )}
        </div>

        {deleteDialog}

        <CommissionSetupFormDialog
          open={editorOpen}
          onOpenChange={setEditorOpen}
          product={product}
          existing={editing}
          onSuccess={() => {
            queryClient.invalidateQueries({
              queryKey: ['setup', 'commission-setups', product?.id],
            });
            setEditorOpen(false);
          }}
        />
      </SheetContent>
    </Sheet>
  );
}

// ── Create / Edit Dialog ──────────────────────────────────────────────────
interface FormDialogProps {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  product: ProductDto | null;
  existing: CommissionSetupDto | null;
  onSuccess: () => void;
}

function CommissionSetupFormDialog({
  open, onOpenChange, product, existing, onSuccess,
}: FormDialogProps) {
  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema) as any,
    defaultValues: { commissionSource: 'BROKER', rate: 10, effectiveFrom: '', effectiveTo: '' },
  });

  useEffect(() => {
    if (!open) return;
    form.reset(existing
      ? {
          commissionSource: existing.commissionSource,
          rate:             existing.rate,
          effectiveFrom:    existing.effectiveFrom,
          effectiveTo:      existing.effectiveTo ?? '',
        }
      : { commissionSource: 'BROKER', rate: 10, effectiveFrom: '', effectiveTo: '' });
  }, [existing, open, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload = {
        commissionSource: values.commissionSource,
        rate:             values.rate,
        effectiveFrom:    values.effectiveFrom,
        effectiveTo:      values.effectiveTo || undefined,
      };
      if (existing) {
        const res = await apiClient.put<{ data: CommissionSetupDto }>(
          `/api/v1/setup/products/${product!.id}/commission-setups/${existing.id}`, payload,
        );
        return res.data.data;
      }
      const res = await apiClient.post<{ data: CommissionSetupDto }>(
        `/api/v1/setup/products/${product!.id}/commission-setups`, payload,
      );
      return res.data.data;
    },
    onSuccess,
    onError: (e) => applyApiErrors(e, form, {
      defaultTitle: existing ? 'Could not update commission rule' : 'Could not add commission rule',
    }),
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{existing ? 'Edit Commission Rule' : 'Add Commission Rule'}</DialogTitle>
          <DialogDescription>
            Per-source rate for {product?.name ?? 'this product'}. Effective dates control when this rule
            applies; leave the end date empty for an open-ended rule.
          </DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="space-y-4">
            <FormRow>
              <FormField control={form.control} name="commissionSource" render={({ field }) => (
                <FormItem>
                  <FormLabel>Source</FormLabel>
                  <Select onValueChange={(v) => field.onChange(v as CommissionSourceType)} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                    <SelectContent>
                      <SelectItem value="BROKER">Broker</SelectItem>
                      <SelectItem value="AGENT">Agent</SelectItem>
                      <SelectItem value="RELATIONSHIP_MANAGER">Relationship Manager</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )} />
              <FormField control={form.control} name="rate" render={({ field }) => (
                <FormItem>
                  <FormLabel>Rate (%)</FormLabel>
                  <FormControl><Input type="number" min={0} max={100} step={0.01} {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )} />
            </FormRow>
            <FormRow>
              <FormField control={form.control} name="effectiveFrom" render={({ field }) => (
                <FormItem>
                  <FormLabel>Effective From</FormLabel>
                  <FormControl><Input type="date" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )} />
              <FormField control={form.control} name="effectiveTo" render={({ field }) => (
                <FormItem>
                  <FormLabel>Effective To <span className="text-muted-foreground">(optional)</span></FormLabel>
                  <FormControl><Input type="date" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )} />
            </FormRow>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : existing ? 'Save Changes' : 'Add Rule'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
