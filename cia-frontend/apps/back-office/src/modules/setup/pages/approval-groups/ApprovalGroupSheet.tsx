import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type ApprovalGroupDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { applyApiErrors } from '@/lib/form-errors';

const levelSchema = z.object({
  levelOrder:     z.coerce.number().int().min(1),
  approverUserId: z.string().min(1, 'Required'),
  approverName:   z.string().optional(),
  maxAmount:      z.coerce.number().min(0),
});

const schema = z.object({
  name:       z.string().min(2, 'Required'),
  entityType: z.string().min(1, 'Required'),
  levels:     z.array(levelSchema).min(1, 'At least one level required'),
});

type FormValues = z.infer<typeof schema>;

const MODULES = ['UNDERWRITING','CLAIMS','FINANCE','ENDORSEMENT','QUOTATION'];

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  group: ApprovalGroupDto | null; onSuccess: () => void;
}

export default function ApprovalGroupSheet({ open, onOpenChange, group, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: { name: '', entityType: '', levels: [{ levelOrder: 1, approverUserId: '', approverName: '', maxAmount: 10_000_000 }] },
  });

  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'levels' });

  useEffect(() => {
    if (group) {
      form.reset({
        name:       group.name,
        entityType: group.entityType,
        levels:     group.levels.map((l, i) => ({
          levelOrder:     l.levelOrder ?? i + 1,
          approverUserId: l.approverUserId,
          approverName:   l.approverName ?? '',
          maxAmount:      l.maxAmount,
        })),
      });
    } else {
      form.reset({ name: '', entityType: '', levels: [{ levelOrder: 1, approverUserId: '', approverName: '', maxAmount: 10_000_000 }] });
    }
  }, [group, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload = {
        ...values,
        levels: values.levels.map((level, index) => ({ ...level, levelOrder: index + 1 })),
      };
      if (group) {
        const res = await apiClient.put<{ data: ApprovalGroupDto }>(
          `/api/v1/setup/approval-groups/${group.id}`, payload,
        );
        return res.data.data;
      }
      const res = await apiClient.post<{ data: ApprovalGroupDto }>(
        '/api/v1/setup/approval-groups', payload,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'approval-groups'] });
      onSuccess();
    },
    onError: (e) => applyApiErrors(e, form, { defaultTitle: group ? 'Could not update approval group' : 'Could not create approval group' }),
  });

  function onSubmit(values: FormValues) {
    save.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{group ? 'Edit Approval Group' : 'New Approval Group'}</SheetTitle>
          <SheetDescription>Configure which module this applies to and the approval levels.</SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">
            <FormRow>
              <FormField control={form.control} name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Group Name</FormLabel>
                    <FormControl><Input placeholder="e.g. Policy Approval" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="entityType"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Entity Type</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select entity" /></SelectTrigger></FormControl>
                      <SelectContent>{MODULES.map((m) => <SelectItem key={m} value={m}>{m}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            <div className="space-y-3">
              <p className="text-sm font-semibold text-foreground">Approval Levels</p>
              {fields.map((f, i) => (
                <div key={f.id} className="rounded-lg border p-4 space-y-3">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-medium text-muted-foreground">Level {i + 1}</p>
                    {fields.length > 1 && (
                      <Button type="button" variant="ghost" size="sm" onClick={() => remove(i)} className="h-7 text-xs text-destructive">
                        Remove
                      </Button>
                    )}
                  </div>
                  <FormRow>
                    <FormField control={form.control} name={`levels.${i}.maxAmount`}
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Max Amount (₦)</FormLabel>
                          <FormControl><Input type="number" {...field} /></FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                    <FormField control={form.control} name={`levels.${i}.approverUserId`}
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Approver User ID</FormLabel>
                          <FormControl><Input placeholder="Keycloak user id" {...field} /></FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                  </FormRow>
                  <FormField control={form.control} name={`levels.${i}.approverName`}
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Approver Name</FormLabel>
                        <FormControl><Input placeholder="Display name" {...field} /></FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              ))}
              <Button type="button" variant="outline" size="sm"
                onClick={() => append({ levelOrder: fields.length + 1, approverUserId: '', approverName: '', maxAmount: 50_000_000 })}>
                + Add Level
              </Button>
            </div>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : group ? 'Save Changes' : 'Create Group'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
