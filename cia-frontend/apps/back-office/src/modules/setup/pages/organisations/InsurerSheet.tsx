import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Textarea,
} from '@cia/ui';
import { apiClient, type InsuranceCompanyDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name:          z.string().min(2, 'Required'),
  rcNumber:      z.string().optional(),
  naicomLicense: z.string().optional(),
  address:       z.string().optional(),
  email:         z.string().email().optional().or(z.literal('')),
  phone:         z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  insurer: InsuranceCompanyDto | null; onSuccess: () => void;
}

export default function InsurerSheet({ open, onOpenChange, insurer, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', rcNumber: '', naicomLicense: '', address: '', email: '', phone: '' },
  });

  useEffect(() => {
    form.reset(insurer ? {
      name: insurer.name,
      rcNumber: insurer.rcNumber ?? '',
      naicomLicense: insurer.naicomLicense ?? '',
      address: insurer.address ?? '',
      email: insurer.email ?? '', phone: insurer.phone ?? '',
    } : { name: '', rcNumber: '', naicomLicense: '', address: '', email: '', phone: '' });
  }, [insurer, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload = {
        ...values,
        rcNumber: values.rcNumber || undefined,
        naicomLicense: values.naicomLicense || undefined,
        address: values.address || undefined,
        email: values.email || undefined,
        phone: values.phone || undefined,
      };
      if (insurer) {
        const res = await apiClient.put<{ data: InsuranceCompanyDto }>(`/api/v1/setup/insurance-companies/${insurer.id}`, payload);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: InsuranceCompanyDto }>('/api/v1/setup/insurance-companies', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'insurance-companies'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: insurer ? 'Could not update insurer' : 'Could not add insurer' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{insurer ? 'Edit Insurance Company' : 'Add Insurance Company'}</SheetTitle>
          <SheetDescription>Counter-party insurers — used in coinsurance participant pickers (Module 3).</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem><FormLabel>Company Name</FormLabel><FormControl><Input placeholder="e.g. AIICO Insurance Plc" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <FormRow>
              <FormField control={form.control} name="rcNumber" render={({ field }) => (
                <FormItem><FormLabel>RC Number</FormLabel><FormControl><Input placeholder="CAC registration" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="naicomLicense" render={({ field }) => (
                <FormItem><FormLabel>NAICOM License</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <FormField control={form.control} name="address" render={({ field }) => (
              <FormItem><FormLabel>Address</FormLabel><FormControl><Textarea rows={2} {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <FormRow>
              <FormField control={form.control} name="email" render={({ field }) => (
                <FormItem><FormLabel>Email</FormLabel><FormControl><Input type="email" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="phone" render={({ field }) => (
                <FormItem><FormLabel>Phone</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : insurer ? 'Save Changes' : 'Add Insurance Company'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
