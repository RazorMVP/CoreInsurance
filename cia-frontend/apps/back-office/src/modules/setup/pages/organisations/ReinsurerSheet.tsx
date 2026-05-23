import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Textarea,
} from '@cia/ui';
import { apiClient, type ReinsuranceCompanyDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name:     z.string().min(2, 'Required'),
  country:  z.string().min(2, 'Required'),
  rcNumber: z.string().optional(),
  address:  z.string().optional(),
  email:    z.email().optional().or(z.literal('')),
  phone:    z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  reinsurer: ReinsuranceCompanyDto | null; onSuccess: () => void;
}

export default function ReinsurerSheet({ open, onOpenChange, reinsurer, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', country: '', rcNumber: '', address: '', email: '', phone: '' },
  });

  useEffect(() => {
    form.reset(reinsurer ? {
      name: reinsurer.name, country: reinsurer.country,
      rcNumber: reinsurer.rcNumber ?? '',
      address: reinsurer.address ?? '',
      email: reinsurer.email ?? '', phone: reinsurer.phone ?? '',
    } : { name: '', country: '', rcNumber: '', address: '', email: '', phone: '' });
  }, [reinsurer, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload = {
        ...values,
        rcNumber: values.rcNumber || undefined,
        address: values.address || undefined,
        email: values.email || undefined,
        phone: values.phone || undefined,
      };
      if (reinsurer) {
        const res = await apiClient.put<{ data: ReinsuranceCompanyDto }>(`/api/v1/setup/reinsurance-companies/${reinsurer.id}`, payload);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: ReinsuranceCompanyDto }>('/api/v1/setup/reinsurance-companies', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'reinsurance-companies'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: reinsurer ? 'Could not update reinsurer' : 'Could not add reinsurer' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{reinsurer ? 'Edit Reinsurer' : 'Add Reinsurer'}</SheetTitle>
          <SheetDescription>Reinsurance counter-parties — referenced by treaties and FAC covers (Module 6).</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Reinsurer Name</FormLabel><FormControl><Input placeholder="e.g. African Reinsurance" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="country" render={({ field }) => (
                <FormItem><FormLabel>Country</FormLabel><FormControl><Input placeholder="e.g. Nigeria" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <FormField control={form.control} name="rcNumber" render={({ field }) => (
              <FormItem><FormLabel>RC Number / Registration ID</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
            )} />
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
                {save.isPending ? 'Saving…' : reinsurer ? 'Save Changes' : 'Add Reinsurer'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
