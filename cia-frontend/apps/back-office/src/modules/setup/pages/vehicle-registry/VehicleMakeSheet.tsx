import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type VehicleMakeDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({ name: z.string().min(2, 'Required').max(100) });
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  make: VehicleMakeDto | null;
  onSuccess: () => void;
}

export default function VehicleMakeSheet({ open, onOpenChange, make, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '' } });

  useEffect(() => { form.reset(make ? { name: make.name } : { name: '' }); }, [make, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (make) {
        const res = await apiClient.put<{ data: VehicleMakeDto }>(`/api/v1/setup/vehicle-makes/${make.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: VehicleMakeDto }>('/api/v1/setup/vehicle-makes', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'vehicle-makes'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: make ? 'Could not update make' : 'Could not add make' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{make ? 'Edit Make' : 'Add Make'}</SheetTitle>
          <SheetDescription>Vehicle makes power the motor-class risk pickers.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem><FormLabel>Make Name</FormLabel><FormControl><Input placeholder="e.g. Toyota" {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : make ? 'Save Changes' : 'Add Make'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
