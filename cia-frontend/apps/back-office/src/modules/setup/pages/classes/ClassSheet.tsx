import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  Input, Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type ClassOfBusinessDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const schema = z.object({
  name: z.string().min(2, 'Required'),
  code: z.string().min(2, 'Required').max(10).toUpperCase(),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  cls: ClassOfBusinessDto | null; onSuccess: () => void;
}

export default function ClassSheet({ open, onOpenChange, cls, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: '', code: '' } });

  useEffect(() => {
    form.reset(cls ? { name: cls.name, code: cls.code } : { name: '', code: '' });
  }, [cls, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (cls) {
        const res = await apiClient.put<{ data: ClassOfBusinessDto }>(
          `/api/v1/setup/classes-of-business/${cls.id}`, values,
        );
        return res.data.data;
      }
      const res = await apiClient.post<{ data: ClassOfBusinessDto }>(
        '/api/v1/setup/classes-of-business', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'classes-of-business'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: cls ? 'Could not update class' : 'Could not create class' }),
  });

  function onSubmit(values: FormValues) {
    save.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-sm">
        <SheetHeader>
          <SheetTitle>{cls ? 'Edit Class' : 'New Class of Business'}</SheetTitle>
          <SheetDescription>Classes group related products together.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            <FormField control={form.control} name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Class Name</FormLabel>
                  <FormControl><Input placeholder="e.g. Motor (Private)" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField control={form.control} name="code"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Code</FormLabel>
                  <FormControl><Input placeholder="e.g. MTR-PRV" className="uppercase" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : cls ? 'Save Changes' : 'Create Class'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
