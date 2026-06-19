import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
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
import { apiClient, type ClauseDto, type ProductDto } from '@cia/api-client';
import { CLAUSE_TYPES } from './clause-types';
import { applyApiErrors } from '@/lib/form-errors';

const clauseSchema = z.object({
  title:         z.string().min(2, 'Required'),
  text:          z.string().min(10, 'Required'),
  type:          z.enum(['STANDARD', 'EXCLUSION', 'SPECIAL_CONDITION', 'WARRANTY']),
  applicability: z.enum(['MANDATORY', 'OPTIONAL']),
  productIds:    z.array(z.string()),   // empty = applies to all products
});
type ClauseFormValues = z.infer<typeof clauseSchema>;

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  clause:       ClauseDto | null;
  products:     ProductDto[];
  onSuccess:    () => void;
}

export default function ClauseSheet({ open, onOpenChange, clause, products, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<ClauseFormValues>({
    resolver:      zodResolver(clauseSchema),
    defaultValues: { title: '', text: '', type: 'STANDARD', applicability: 'OPTIONAL', productIds: [] },
  });

  useEffect(() => {
    form.reset(
      clause
        ? { title: clause.title, text: clause.text, type: clause.type, applicability: clause.applicability, productIds: clause.productIds }
        : { title: '', text: '', type: 'STANDARD', applicability: 'OPTIONAL', productIds: [] },
    );
  }, [clause, open, form]);

  const save = useMutation({
    mutationFn: async (values: ClauseFormValues) => {
      if (clause) {
        const res = await apiClient.put<{ data: ClauseDto }>(`/api/v1/setup/clauses/${clause.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: ClauseDto }>('/api/v1/setup/clauses', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'clauses'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: clause ? 'Could not update clause' : 'Could not add clause' }),
  });

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
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-5">

            <FormField control={form.control} name="title" render={({ field }) => (
              <FormItem>
                <FormLabel>Clause Title</FormLabel>
                <FormControl><Input placeholder="e.g. Third Party Liability" {...field} /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="text" render={({ field }) => (
              <FormItem>
                <FormLabel>Clause Text</FormLabel>
                <FormControl>
                  <Textarea placeholder="Enter the full clause wording…" className="min-h-[100px] resize-y" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="type" render={({ field }) => (
              <FormItem>
                <FormLabel>Type</FormLabel>
                <Select value={field.value} onValueChange={field.onChange}>
                  <FormControl><SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger></FormControl>
                  <SelectContent>
                    {CLAUSE_TYPES.map(t => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="applicability" render={({ field }) => (
              <FormItem>
                <FormLabel>Applicability</FormLabel>
                <div className="flex items-start gap-3 pt-1">
                  <Switch checked={field.value === 'MANDATORY'} onCheckedChange={(checked) => field.onChange(checked ? 'MANDATORY' : 'OPTIONAL')} />
                  <div>
                    <p className="text-sm font-medium leading-none">{field.value === 'MANDATORY' ? 'Mandatory' : 'Optional'}</p>
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

            <FormField control={form.control} name="productIds" render={({ field: productsField }) => (
              <FormItem>
                <FormLabel>Products <span className="text-xs font-normal text-muted-foreground">(none = applies to all)</span></FormLabel>
                {products.length === 0 ? (
                  <p className="text-xs text-muted-foreground">No products configured yet.</p>
                ) : (
                  <div className="rounded-md border divide-y max-h-[160px] overflow-y-auto">
                    {products.map(p => (
                      <label key={p.id} className="flex items-center gap-2.5 px-3 py-2 cursor-pointer hover:bg-secondary">
                        <Checkbox
                          checked={(productsField.value as string[]).includes(p.id)}
                          onCheckedChange={() => {
                            const current = productsField.value as string[];
                            productsField.onChange(current.includes(p.id) ? current.filter(id => id !== p.id) : [...current, p.id]);
                          }}
                        />
                        <span className="text-sm">{p.name}</span>
                      </label>
                    ))}
                  </div>
                )}
                <FormMessage />
              </FormItem>
            )} />

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save Clause'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
