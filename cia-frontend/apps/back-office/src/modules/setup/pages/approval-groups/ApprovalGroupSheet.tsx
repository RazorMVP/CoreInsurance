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
import { applyApiErrors } from '@/lib/form-errors';

// Mirrors com.nubeero.cia.setup.approval.dto.ApprovalGroupRequest 1:1.
// Aligned with backend in Session 99 / Backlog A1b:
//   - `module` (UI alias) → `entityType`
//   - Per-level shape moved from multi-approver + min/max range to single
//     approver + maxAmount, keyed by levelOrder. Backend infers each level's
//     min-amount band from the previous level's maxAmount.
const levelSchema = z.object({
  levelOrder:     z.coerce.number().min(1),
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

// Entity types that have an approval-group flow. Same vocabulary the backend
// uses in approval-group records. UI labels live in ApprovalGroupsPage.
const ENTITY_TYPES = ['POLICY', 'CLAIM', 'ENDORSEMENT', 'QUOTE', 'FINANCE_RECEIPT', 'FINANCE_PAYMENT'];

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
    defaultValues: {
      name:       '',
      entityType: '',
      levels:     [{ levelOrder: 1, approverUserId: '', approverName: '', maxAmount: 10_000_000 }],
    },
  });

  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'levels' });

  useEffect(() => {
    if (group) {
      form.reset({
        name:       group.name,
        entityType: group.entityType,
        levels:     group.levels.map((l) => ({
          levelOrder:     l.levelOrder,
          approverUserId: l.approverUserId,
          approverName:   l.approverName,
          maxAmount:      l.maxAmount,
        })),
      });
    } else {
      form.reset({
        name:       '',
        entityType: '',
        levels:     [{ levelOrder: 1, approverUserId: '', approverName: '', maxAmount: 10_000_000 }],
      });
    }
  }, [group, form]);

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      // Resolve approverName per level from the loaded users list so the
      // backend doesn't have to denormalise on every PUT. (Backend will
      // resolve from approverUserId regardless, but sending the name keeps
      // the request payload self-describing for audit logs.)
      const payload: FormValues = {
        ...values,
        levels: values.levels.map((l) => ({
          ...l,
          approverName: approvers.find((a) => a.id === l.approverUserId)?.name ?? l.approverName ?? '',
        })),
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
          <SheetDescription>Configure which entity type this applies to and the approval ladder.</SheetDescription>
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
                      <FormControl><SelectTrigger><SelectValue placeholder="Select entity type" /></SelectTrigger></FormControl>
                      <SelectContent>{ENTITY_TYPES.map((m) => <SelectItem key={m} value={m}>{m.replace(/_/g, ' ')}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            <div className="space-y-3">
              <p className="text-sm font-semibold text-foreground">Approval Levels</p>
              <p className="text-xs text-muted-foreground">
                Each level escalates from the previous: level 1 covers amounts up to its max; level 2 picks up beyond that, and so on.
              </p>
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
                    <FormField control={form.control} name={`levels.${i}.levelOrder`}
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Order</FormLabel>
                          <FormControl><Input type="number" min={1} {...field} /></FormControl>
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
                  <FormField control={form.control} name={`levels.${i}.approverUserId`}
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Approver</FormLabel>
                        <Select onValueChange={field.onChange} value={field.value}>
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
                onClick={() => append({
                  levelOrder:     fields.length + 1,
                  approverUserId: '',
                  approverName:   '',
                  maxAmount:      50_000_000,
                })}>
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
