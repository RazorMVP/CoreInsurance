import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Textarea,
} from '@cia/ui';
import { apiClient, type AdjusterDto, type AdjusterType } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name:          z.string().min(2, 'Required'),
  code:          z.string().min(2, 'Required').max(20),
  type:          z.enum(['INTERNAL', 'EXTERNAL']),
  licenseNumber: z.string().optional(),
  email:         z.string().email().optional().or(z.literal('')),
  phone:         z.string().optional(),
  address:       z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  adjuster: AdjusterDto | null; onSuccess: () => void;
}

export default function AdjusterSheet({ open, onOpenChange, adjuster, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', code: '', type: 'EXTERNAL', licenseNumber: '', email: '', phone: '', address: '' },
  });

  useEffect(() => {
    form.reset(adjuster ? {
      name: adjuster.name, code: adjuster.code, type: adjuster.type,
      licenseNumber: adjuster.licenseNumber ?? '',
      email: adjuster.email ?? '', phone: adjuster.phone ?? '',
      address: adjuster.address ?? '',
    } : { name: '', code: '', type: 'EXTERNAL', licenseNumber: '', email: '', phone: '', address: '' });
  }, [adjuster, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload = {
        ...values,
        licenseNumber: values.licenseNumber || undefined,
        email: values.email || undefined,
        phone: values.phone || undefined,
        address: values.address || undefined,
      };
      if (adjuster) {
        const res = await apiClient.put<{ data: AdjusterDto }>(`/api/v1/setup/adjusters/${adjuster.id}`, payload);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: AdjusterDto }>('/api/v1/setup/adjusters', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'adjusters'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: adjuster ? 'Could not update adjuster' : 'Could not add adjuster' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{adjuster ? 'Edit Adjuster' : 'Add Adjuster'}</SheetTitle>
          <SheetDescription>NAICOM-licensed loss adjusters perform post-loss claim assessment. Internal = staff; External = independent firms.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Adjuster Name</FormLabel><FormControl><Input placeholder="Firm or individual name" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="ADJ001" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <FormRow>
              <FormField control={form.control} name="type" render={({ field }) => (
                <FormItem><FormLabel>Type</FormLabel>
                  <Select onValueChange={(v) => field.onChange(v as AdjusterType)} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                    <SelectContent>
                      <SelectItem value="INTERNAL">Internal</SelectItem>
                      <SelectItem value="EXTERNAL">External</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )} />
              <FormField control={form.control} name="licenseNumber" render={({ field }) => (
                <FormItem><FormLabel>NAICOM License</FormLabel><FormControl><Input placeholder="Optional for INTERNAL" {...field} /></FormControl><FormMessage /></FormItem>
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
                {save.isPending ? 'Saving…' : adjuster ? 'Save Changes' : 'Add Adjuster'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
