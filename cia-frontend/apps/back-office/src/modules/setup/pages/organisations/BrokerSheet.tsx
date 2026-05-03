import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type BrokerDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';

const schema = z.object({
  name:          z.string().min(2, 'Required'),
  code:          z.string().min(2, 'Required'),
  email:         z.string().email(),
  phone:         z.string().min(7),
  contactPerson: z.string().min(2, 'Required'),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  broker: BrokerDto | null; onSuccess: () => void;
}

export default function BrokerSheet({ open, onOpenChange, broker, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver:      zodResolver(schema),
    defaultValues: { name: '', code: '', email: '', phone: '', contactPerson: '' },
  });

  useEffect(() => {
    form.reset(broker ? {
      name: broker.name, code: broker.code, email: broker.email,
      phone: broker.phone, contactPerson: broker.contactPerson,
    } : { name: '', code: '', email: '', phone: '', contactPerson: '' });
  }, [broker, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (broker) {
        const res = await apiClient.put<{ data: BrokerDto }>(
          `/api/v1/setup/brokers/${broker.id}`, values,
        );
        return res.data.data;
      }
      const res = await apiClient.post<{ data: BrokerDto }>(
        '/api/v1/setup/brokers', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'brokers'] });
      onSuccess();
    },
  });

  function onSubmit(values: FormValues) {
    save.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{broker ? 'Edit Broker' : 'Add Broker'}</SheetTitle>
          <SheetDescription>Broker details are used on policy documents and commission tracking.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name"
                render={({ field }) => (<FormItem><FormLabel>Broker Name</FormLabel><FormControl><Input placeholder="e.g. Leadway Brokers Ltd" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="code"
                render={({ field }) => (<FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="LWB" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>
            <FormField control={form.control} name="contactPerson"
              render={({ field }) => (<FormItem><FormLabel>Contact Person</FormLabel><FormControl><Input placeholder="Full name" {...field} /></FormControl><FormMessage /></FormItem>)} />
            <FormRow>
              <FormField control={form.control} name="email"
                render={({ field }) => (<FormItem><FormLabel>Email</FormLabel><FormControl><Input type="email" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="phone"
                render={({ field }) => (<FormItem><FormLabel>Phone</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>)} />
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
