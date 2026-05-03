import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type ApprovalGroupDto, type UserDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';

const levelSchema = z.object({
  minAmount:    z.coerce.number().min(0),
  maxAmount:    z.coerce.number().min(1),
  approverIds:  z.array(z.string()).min(1),
});

const schema = z.object({
  name:   z.string().min(2, 'Required'),
  module: z.string().min(1, 'Required'),
  levels: z.array(levelSchema).min(1, 'At least one level required'),
});

type FormValues = z.infer<typeof schema>;

const MODULES = ['UNDERWRITING','CLAIMS','FINANCE','ENDORSEMENT','QUOTATION'];

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  group: ApprovalGroupDto | null; onSuccess: () => void;
}

export default function ApprovalGroupSheet({ open, onOpenChange, group, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const usersQuery = useQuery<UserDto[]>({
    queryKey: ['setup', 'users'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: UserDto[] }>('/api/v1/setup/users');
      return res.data.data;
    },
    enabled: open,
  });
  const approvers = (usersQuery.data ?? []).map((u) => ({
    id:   u.id,
    name: `${u.firstName} ${u.lastName}`,
  }));

  const form = useForm<FormValues>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver:      zodResolver(schema) as any,
    defaultValues: { name: '', module: '', levels: [{ minAmount: 0, maxAmount: 10_000_000, approverIds: [] }] },
  });

  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'levels' });

  useEffect(() => {
    if (group) {
      form.reset({
        name:   group.name,
        module: group.module,
        levels: group.levels.map((l) => ({ minAmount: l.minAmount, maxAmount: l.maxAmount, approverIds: l.approverIds })),
      });
    } else {
      form.reset({ name: '', module: '', levels: [{ minAmount: 0, maxAmount: 10_000_000, approverIds: [] }] });
    }
  }, [group, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (group) {
        const res = await apiClient.put<{ data: ApprovalGroupDto }>(
          `/api/v1/setup/approval-groups/${group.id}`, values,
        );
        return res.data.data;
      }
      const res = await apiClient.post<{ data: ApprovalGroupDto }>(
        '/api/v1/setup/approval-groups', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'approval-groups'] });
      onSuccess();
    },
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
              <FormField control={form.control} name="module"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Module</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select module" /></SelectTrigger></FormControl>
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
                    <FormField control={form.control} name={`levels.${i}.minAmount`}
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Min Amount (₦)</FormLabel>
                          <FormControl><Input type="number" {...field} /></FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                    <FormField control={form.control} name={`levels.${i}.maxAmount`}
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Max Amount (₦)</FormLabel>
                          <FormControl><Input type="number" {...field} /></FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                  </FormRow>
                  <FormField control={form.control} name={`levels.${i}.approverIds`}
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Approver</FormLabel>
                        <Select
                          onValueChange={(v) => field.onChange([v])}
                          value={field.value[0] ?? ''}
                        >
                          <FormControl><SelectTrigger><SelectValue placeholder="Select approver" /></SelectTrigger></FormControl>
                          <SelectContent>
                            {approvers.map((a) => <SelectItem key={a.id} value={a.id}>{a.name}</SelectItem>)}
                          </SelectContent>
                        </Select>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              ))}
              <Button type="button" variant="outline" size="sm"
                onClick={() => append({ minAmount: 0, maxAmount: 50_000_000, approverIds: [] })}>
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
