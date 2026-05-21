import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input, Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type SbuDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name: z.string().min(2, 'Required'),
  code: z.string().min(2, 'Required').max(20),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  sbu: SbuDto | null; onSuccess: () => void;
}

export default function SbuSheet({ open, onOpenChange, sbu, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', code: '' },
  });

  useEffect(() => {
    form.reset(sbu ? { name: sbu.name, code: sbu.code } : { name: '', code: '' });
  }, [sbu, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (sbu) {
        const res = await apiClient.put<{ data: SbuDto }>(`/api/v1/setup/sbus/${sbu.id}`, values);
        return res.data.data;
      }
      const res = await apiClient.post<{ data: SbuDto }>('/api/v1/setup/sbus', values);
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'sbus'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: sbu ? 'Could not update SBU' : 'Could not add SBU' }),
  });

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{sbu ? 'Edit SBU' : 'Add SBU'}</SheetTitle>
          <SheetDescription>Strategic Business Units group branches for portfolio-level reporting.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mt-6 space-y-4">
            <FormRow>
              <FormField control={form.control} name="name" render={({ field }) => (
                <FormItem><FormLabel>SBU Name</FormLabel><FormControl><Input placeholder="e.g. Retail" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="code" render={({ field }) => (
                <FormItem><FormLabel>Code</FormLabel><FormControl><Input placeholder="RET" className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : sbu ? 'Save Changes' : 'Add SBU'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
