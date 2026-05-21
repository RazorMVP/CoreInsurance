import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type SurveyorDto, type SurveyorType } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name:          z.string().min(2, 'Required'),
  type:          z.enum(['INTERNAL', 'EXTERNAL']),
  licenseNumber: z.string().optional(),
  email:         z.string().email().optional().or(z.literal('')),
  phone:         z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  surveyor: SurveyorDto | null; onSuccess: () => void;
}

export default function SurveyorSheet({ open, onOpenChange, surveyor, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', type: 'EXTERNAL', licenseNumber: '', email: '', phone: '' },
  });

  useEffect(() => {
    form.reset(surveyor ? {
      name: surveyor.name, type: surveyor.type,
      licenseNumber: surveyor.licenseNumber ?? '',
      email: surveyor.email ?? '', phone: surveyor.phone ?? '',
    } : { name: '', type: 'EXTERNAL', licenseNumber: '', email: '', phone: '' });
  }, [surveyor, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload = {
        ...values,
        licenseNumber: values.licenseNumber || undefined,
        email: values.email || undefined,
        phone: values.phone || undefined,
      };
      if (surveyor) {
        const res = await apiClient.put<{ data: SurveyorDto }>(`/api/v1/setup/surveyors/${surveyor.id}`, payload);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: SurveyorDto }>('/api/v1/setup/surveyors', payload);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'surveyors'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: surveyor ? 'Could not update surveyor' : 'Could not add surveyor' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{surveyor ? 'Edit Surveyor' : 'Add Surveyor'}</SheetTitle>
          <SheetDescription>Pre-loss survey + claim inspection assignments draw from this list.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>Name</FormLabel><FormControl><Input placeholder="Firm or individual name" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="type" render={({ field }) => (
                <FormItem><FormLabel>Type</FormLabel>
                  <Select onValueChange={(v) => field.onChange(v as SurveyorType)} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                    <SelectContent>
                      <SelectItem value="INTERNAL">Internal</SelectItem>
                      <SelectItem value="EXTERNAL">External</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )} />
            </FormRow>
            <FormField control={form.control} name="licenseNumber" render={({ field }) => (
              <FormItem><FormLabel>NAICOM License Number</FormLabel><FormControl><Input placeholder="Optional for INTERNAL" {...field} /></FormControl><FormMessage /></FormItem>
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
                {save.isPending ? 'Saving…' : surveyor ? 'Save Changes' : 'Add Surveyor'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
