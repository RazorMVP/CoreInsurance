import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Textarea,
} from '@cia/ui';
import { apiClient, type BrokerDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name:          z.string().min(2, 'Required'),
  code:          z.string().min(2, 'Required').max(20),
  rcNumber:      z.string().optional(),
  licenseNumber: z.string().optional(),
  address:       z.string().optional(),
  email:         z.string().email().optional().or(z.literal('')),
  phone:         z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  broker: BrokerDto | null; onSuccess: () => void;
}

export default function BrokerSheet({ open, onOpenChange, broker, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', code: '', rcNumber: '', licenseNumber: '', address: '', email: '', phone: '' },
  });

  useEffect(() => {
    form.reset(broker ? {
      name: broker.name, code: broker.code,
      rcNumber: broker.rcNumber ?? '',
      licenseNumber: broker.licenseNumber ?? '',
      address: broker.address ?? '',
      email: broker.email ?? '', phone: broker.phone ?? '',
    } : { name: '', code: '', rcNumber: '', licenseNumber: '', address: '', email: '', phone: '' });
  }, [broker, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      // Normalise empty strings → undefined for optional fields
      const payload = {
        ...values,
        rcNumber: values.rcNumber || undefined,
        licenseNumber: values.licenseNumber || undefined,
        address: values.address || undefined,
        email: values.email || undefined,
        phone: values.phone || undefined,
      };
      if (broker) {
        const res = await apiClient.put<{ data: BrokerDto }>(`/api/v1/setup/brokers/${broker.id}`, payload);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: BrokerDto }>('/api/v1/setup/brokers', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'brokers'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: broker ? 'Could not update broker' : 'Could not add broker' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{broker ? 'Edit Broker' : 'Add Broker'}</SheetTitle>
          <SheetDescription>Broker details are used on policy documents and commission tracking.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Broker Name</FormLabel><FormControl><Input placeholder="e.g. Leadway Brokers Ltd" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="LWB" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <FormRow>
              <FormField control={form.control} name="rcNumber" render={({ field }) => (
                <FormItem><FormLabel>RC Number</FormLabel><FormControl><Input placeholder="CAC registration number" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="licenseNumber" render={({ field }) => (
                <FormItem><FormLabel>NAICOM License</FormLabel><FormControl><Input placeholder="NAICOM broker licence number" {...field} /></FormControl><FormMessage /></FormItem>
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
                {save.isPending ? 'Saving…' : broker ? 'Save Changes' : 'Add Broker'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
