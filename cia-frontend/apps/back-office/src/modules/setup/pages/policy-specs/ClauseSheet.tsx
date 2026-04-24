import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm, useController } from 'react-hook-form';
import { z } from 'zod';
import {
  Button,
  Checkbox,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Switch,
  Textarea,
  Input,
} from '@cia/ui';
import type { ClauseRow, ClauseSavePayload } from './clause-types';
import { PRODUCTS, CLAUSE_TYPES } from './clause-types';

// ── Schema ───────────────────────────────────────────────────────────────────
const clauseSchema = z.object({
  title:         z.string().min(2, 'Required'),
  text:          z.string().min(10, 'Required'),
  type:          z.enum(['STANDARD', 'EXCLUSION', 'SPECIAL_CONDITION', 'WARRANTY']),
  applicability: z.enum(['MANDATORY', 'OPTIONAL']),
  productIds:    z.array(z.string()).min(1, 'Select at least one product'),
});
type ClauseFormValues = z.infer<typeof clauseSchema>;

// ── Props ────────────────────────────────────────────────────────────────────
interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  clause:       ClauseRow | null;
  onSave:       (values: ClauseSavePayload) => void;
}

export default function ClauseSheet({ open, onOpenChange, clause, onSave }: Props) {
  const form = useForm<ClauseFormValues>({
    resolver:      zodResolver(clauseSchema) as any,
    defaultValues: { title: '', text: '', type: 'STANDARD', applicability: 'OPTIONAL', productIds: [] },
  });

  useEffect(() => {
    form.reset(
      clause
        ? { title: clause.title, text: clause.text, type: clause.type, applicability: clause.applicability, productIds: clause.productIds }
        : { title: '', text: '', type: 'STANDARD', applicability: 'OPTIONAL', productIds: [] },
    );
  }, [clause, open]);

  const { field: productIdsField } = useController({ name: 'productIds', control: form.control });

  function toggleProduct(id: string) {
    const current: string[] = productIdsField.value ?? [];
    productIdsField.onChange(
      current.includes(id) ? current.filter(p => p !== id) : [...current, id],
    );
  }

  function onSubmit(values: ClauseFormValues) {
    onSave({ title: values.title, text: values.text, type: values.type, applicability: values.applicability, productIds: values.productIds, id: clause?.id ?? undefined });
    onOpenChange(false);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-[440px] sm:max-w-[440px] overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{clause ? 'Edit Clause' : 'Add Clause'}</SheetTitle>
          <SheetDescription>
            {clause ? 'Update the clause details below.' : 'Define a new clause for the policy document library.'}
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">

            {/* Title */}
            <FormField control={form.control} name="title" render={({ field }) => (
              <FormItem>
                <FormLabel>Clause Title</FormLabel>
                <FormControl><Input placeholder="e.g. Third Party Liability" {...field} /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            {/* Text */}
            <FormField control={form.control} name="text" render={({ field }) => (
              <FormItem>
                <FormLabel>Clause Text</FormLabel>
                <FormControl>
                  <Textarea
                    placeholder="Enter the full clause wording…"
                    className="min-h-[100px] resize-y"
                    {...field}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )} />

            {/* Type */}
            <FormField control={form.control} name="type" render={({ field }) => (
              <FormItem>
                <FormLabel>Type</FormLabel>
                <Select value={field.value} onValueChange={field.onChange}>
                  <FormControl>
                    <SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {CLAUSE_TYPES.map(t => (
                      <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />

            {/* Applicability toggle */}
            <FormField control={form.control} name="applicability" render={({ field }) => (
              <FormItem>
                <FormLabel>Applicability</FormLabel>
                <div className="flex items-start gap-3 pt-1">
                  <Switch
                    checked={field.value === 'MANDATORY'}
                    onCheckedChange={(checked) => field.onChange(checked ? 'MANDATORY' : 'OPTIONAL')}
                  />
                  <div>
                    <p className="text-sm font-medium leading-none">
                      {field.value === 'MANDATORY' ? 'Mandatory' : 'Optional'}
                    </p>
                    <p className="text-xs text-muted-foreground mt-1">
                      {field.value === 'MANDATORY'
                        ? 'Auto-applied to all new policies for selected products'
                        : 'Available to add manually on individual policies'}
                    </p>
                  </div>
                </div>
                <FormMessage />
              </FormItem>
            )} />

            {/* Products multi-select */}
            <FormItem>
              <FormLabel>Products</FormLabel>
              {/* Selected chips */}
              {productIdsField.value.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mb-2">
                  {PRODUCTS.filter(p => productIdsField.value.includes(p.id)).map(p => (
                    <span
                      key={p.id}
                      className="inline-flex items-center gap-1 rounded-md bg-teal-50 px-2 py-0.5 text-xs font-medium text-teal-700"
                    >
                      {p.name}
                      <button
                        type="button"
                        onClick={() => toggleProduct(p.id)}
                        className="hover:text-teal-900"
                      >
                        ✕
                      </button>
                    </span>
                  ))}
                </div>
              )}
              {/* Checkbox list */}
              <div className="rounded-md border divide-y max-h-[160px] overflow-y-auto">
                {PRODUCTS.map(p => (
                  <label
                    key={p.id}
                    className="flex items-center gap-2.5 px-3 py-2 cursor-pointer hover:bg-secondary"
                  >
                    <Checkbox
                      checked={productIdsField.value.includes(p.id)}
                      onCheckedChange={() => toggleProduct(p.id)}
                    />
                    <span className="text-sm">{p.name}</span>
                  </label>
                ))}
              </div>
              {form.formState.errors.productIds && (
                <p className="text-sm font-medium text-destructive">
                  {form.formState.errors.productIds.message}
                </p>
              )}
            </FormItem>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit">Save Clause</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
