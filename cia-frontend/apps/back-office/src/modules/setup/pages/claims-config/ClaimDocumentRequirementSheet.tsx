import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Switch,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type ClaimDocumentRequirementDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

// ClaimDocumentType enum (cia-claims/ClaimDocumentType.java) — UI option list, not API data.
const DOCUMENT_TYPES = [
  'CLAIM_FORM', 'POLICE_REPORT', 'SURVEY_REPORT', 'MEDICAL_REPORT',
  'PHOTOS', 'REPAIR_ESTIMATE', 'DISCHARGE_VOUCHER', 'OTHER',
] as const;

const schema = z.object({
  documentName: z.string().min(2, 'Required').max(150),
  mandatory:    z.boolean(),
  documentType: z.string().min(1, 'Select a type'),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  productId: string;
  requirement: ClaimDocumentRequirementDto | null;
  onSuccess: () => void;
}

export default function ClaimDocumentRequirementSheet({ open, onOpenChange, productId, requirement, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { documentName: '', mandatory: true, documentType: 'CLAIM_FORM' } });

  useEffect(() => {
    form.reset(requirement
      ? { documentName: requirement.documentName, mandatory: requirement.mandatory, documentType: requirement.documentType || 'OTHER' }
      : { documentName: '', mandatory: true, documentType: 'CLAIM_FORM' });
  }, [requirement, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const base = `/api/v1/setup/products/${productId}/claim-document-requirements`;
      if (requirement) {
        const res = await apiClient.put<{ data: ClaimDocumentRequirementDto }>(`${base}/${requirement.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: ClaimDocumentRequirementDto }>(base, values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'claim-document-requirements', productId] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: requirement ? 'Could not update document' : 'Could not add document' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{requirement ? 'Edit Required Document' : 'Add Required Document'}</SheetTitle>
          <SheetDescription>Documents a claim on this product must supply.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="documentName" render={({ field }) => (
              <FormItem><FormLabel>Document Name</FormLabel><FormControl><Input placeholder="e.g. Police Report" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <FormField control={form.control} name="documentType" render={({ field }) => (
              <FormItem>
                <FormLabel>Type</FormLabel>
                <Select onValueChange={field.onChange} value={field.value || ''}>
                  <FormControl><SelectTrigger><SelectValue placeholder="Select a type…" /></SelectTrigger></FormControl>
                  <SelectContent>{DOCUMENT_TYPES.map((t) => (<SelectItem key={t} value={t}>{t.replace(/_/g, ' ')}</SelectItem>))}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />
            <FormField control={form.control} name="mandatory" render={({ field }) => (
              <FormItem className="flex items-center justify-between rounded-md border p-3">
                <div><FormLabel>Mandatory</FormLabel><FormDescription>Block claim approval until supplied.</FormDescription></div>
                <FormControl><Switch checked={field.value} onCheckedChange={field.onChange} /></FormControl>
              </FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : requirement ? 'Save Changes' : 'Add Document'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
