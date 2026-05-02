import { useState } from 'react';
import {
  Button, Card, CardContent, CardHeader, CardTitle,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  Input, Label,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Separator,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import type { DiscountType, LoadingType, QuoteConfig, CalcSequence } from './quote-config-types';
import { MOCK_DISCOUNT_TYPES, MOCK_LOADING_TYPES, MOCK_QUOTE_CONFIG } from './quote-config-types';

// ── Type editor dialog ────────────────────────────────────────────────────────
const typeSchema = z.object({ name: z.string().min(2, 'Name must be at least 2 characters') });
type TypeForm = z.infer<typeof typeSchema>;

function TypeEditorDialog({
  open, onOpenChange, initial, onSave, title,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  initial: { name: string } | null;
  onSave: (name: string) => void;
  title: string;
}) {
  const form = useForm<TypeForm>({
    resolver: zodResolver(typeSchema),
    values:   { name: initial?.name ?? '' },
  });

  function handleSubmit(v: TypeForm) {
    onSave(v.name);
    onOpenChange(false);
    form.reset();
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>{initial ? `Edit ${title}` : `Add ${title}`}</DialogTitle>
          <DialogDescription>
            Set the type name only — the rate is configured by the underwriter at quote time.
          </DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(handleSubmit)} className="space-y-4 pt-2">
            <FormField control={form.control} name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Type Name</FormLabel>
                  <FormControl><Input placeholder="e.g. No Claims Discount (NCD)" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit">Save</Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}

// ── Simple list manager (shared for discount + loading types) ─────────────────
function TypeListManager({
  label, items, onAdd, onEdit, onDelete,
}: {
  label:    string;
  items:    { id: string; name: string }[];
  onAdd:    (name: string) => void;
  onEdit:   (id: string, name: string) => void;
  onDelete: (id: string) => void;
}) {
  const [dialogOpen,  setDialogOpen]  = useState(false);
  const [editing,     setEditing]     = useState<{ id: string; name: string } | null>(null);
  const [confirmId,   setConfirmId]   = useState<string | null>(null);

  function openCreate() { setEditing(null); setDialogOpen(true); }
  function openEdit(item: { id: string; name: string }) { setEditing(item); setDialogOpen(true); }

  function handleSave(name: string) {
    if (editing) onEdit(editing.id, name);
    else         onAdd(name);
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-sm font-semibold text-foreground">{label}</p>
        <Button size="sm" variant="outline" onClick={openCreate}>+ Add Type</Button>
      </div>

      {items.length === 0 ? (
        <p className="text-sm text-muted-foreground py-4 text-center">
          No {label.toLowerCase()} configured yet.
        </p>
      ) : (
        <div className="divide-y rounded-md border">
          {items.map(item => (
            <div key={item.id} className="flex items-center justify-between px-3 py-2.5">
              <span className="text-sm text-foreground">{item.name}</span>
              <div className="flex gap-1">
                <Button size="sm" variant="ghost" className="h-7 text-xs" onClick={() => openEdit(item)}>Edit</Button>
                <Button size="sm" variant="ghost" className="h-7 text-xs text-destructive hover:text-destructive" onClick={() => setConfirmId(item.id)}>Delete</Button>
              </div>
            </div>
          ))}
        </div>
      )}

      <TypeEditorDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        initial={editing}
        onSave={handleSave}
        title={label.slice(0, -1)}
      />

      <Dialog open={!!confirmId} onOpenChange={() => setConfirmId(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete type?</DialogTitle>
            <DialogDescription>
              Existing quotes that use this type will retain the label. New quotes will no longer see it.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmId(null)}>Cancel</Button>
            <Button variant="destructive" onClick={() => { onDelete(confirmId!); setConfirmId(null); }}>Delete</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

// ── Main tab ──────────────────────────────────────────────────────────────────
export default function QuotesConfigTab() {
  const [discountTypes, setDiscountTypes] = useState<DiscountType[]>(MOCK_DISCOUNT_TYPES);
  const [loadingTypes,  setLoadingTypes]  = useState<LoadingType[]>(MOCK_LOADING_TYPES);
  const [config,        setConfig]        = useState<QuoteConfig>(MOCK_QUOTE_CONFIG);
  const [saved,         setSaved]         = useState(false);

  // Discount type CRUD
  function addDiscount(name: string)             { setDiscountTypes(p => [...p, { id: crypto.randomUUID(), name }]); }
  function editDiscount(id: string, name: string){ setDiscountTypes(p => p.map(t => t.id === id ? { ...t, name } : t)); }
  function deleteDiscount(id: string)            { setDiscountTypes(p => p.filter(t => t.id !== id)); }

  // Loading type CRUD
  function addLoading(name: string)              { setLoadingTypes(p => [...p, { id: crypto.randomUUID(), name }]); }
  function editLoading(id: string, name: string) { setLoadingTypes(p => p.map(t => t.id === id ? { ...t, name } : t)); }
  function deleteLoading(id: string)             { setLoadingTypes(p => p.filter(t => t.id !== id)); }

  function handleSaveConfig() {
    // TODO: PUT /api/v1/setup/quote-config
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  }

  return (
    <div className="space-y-6">
      {/* Discount Types */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Discount Types</CardTitle>
          <p className="text-sm text-muted-foreground">
            Define named discount types available to underwriters when building quotes.
            The actual rate or amount is set by the underwriter per risk item.
          </p>
        </CardHeader>
        <CardContent>
          <TypeListManager
            label="Discount Types"
            items={discountTypes}
            onAdd={addDiscount}
            onEdit={editDiscount}
            onDelete={deleteDiscount}
          />
        </CardContent>
      </Card>

      {/* Loading Types */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Loading Types</CardTitle>
          <p className="text-sm text-muted-foreground">
            Define named loading types available to underwriters. Loadings increase the gross premium
            to account for adverse risk factors.
          </p>
        </CardHeader>
        <CardContent>
          <TypeListManager
            label="Loading Types"
            items={loadingTypes}
            onAdd={addLoading}
            onEdit={editLoading}
            onDelete={deleteLoading}
          />
        </CardContent>
      </Card>

      {/* Quote Settings */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Quote Settings</CardTitle>
          <p className="text-sm text-muted-foreground">
            Global settings applied to all quotes. These appear on downloaded PDFs and control
            premium calculation behaviour.
          </p>
        </CardHeader>
        <CardContent className="space-y-5">
          {/* Validity period */}
          <div className="space-y-1.5">
            <Label>Quote Validity Period (days)</Label>
            <div className="flex items-center gap-3">
              <Input
                type="number"
                min={1}
                max={365}
                className="w-32"
                value={config.validityDays}
                onChange={e => setConfig(p => ({ ...p, validityDays: Number(e.target.value) }))}
              />
              <span className="text-sm text-muted-foreground">
                Quotes expire {config.validityDays} day{config.validityDays !== 1 ? 's' : ''} after the issue date.
              </span>
            </div>
          </div>

          <Separator />

          {/* Calculation sequence */}
          <div className="space-y-1.5">
            <Label>Premium Calculation Sequence</Label>
            <Select
              value={config.calcSequence}
              onValueChange={(v) => setConfig(p => ({ ...p, calcSequence: v as CalcSequence }))}
            >
              <SelectTrigger className="w-72">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="LOADING_FIRST">Loading first, then Discount</SelectItem>
                <SelectItem value="DISCOUNT_FIRST">Discount first, then Loading</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              {config.calcSequence === 'LOADING_FIRST'
                ? 'Net = (Gross + Loading) − Discount. Discount applied to the loaded premium.'
                : 'Net = (Gross − Discount) + Loading. Loading applied to the discounted premium.'}
            </p>
          </div>

          <div className="pt-2">
            <Button onClick={handleSaveConfig}>
              {saved ? '✓ Saved' : 'Save Settings'}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
