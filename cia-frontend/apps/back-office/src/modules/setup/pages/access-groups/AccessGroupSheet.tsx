import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Checkbox, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormSection, Input, Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import type { AccessGroupDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

const ALL_PERMISSIONS = [
  { module: 'Setup',         perms: ['setup:view','setup:create','setup:update'] },
  { module: 'Customers',     perms: ['customers:view','customers:create','customers:update'] },
  { module: 'Underwriting',  perms: ['underwriting:view','underwriting:create','underwriting:update','underwriting:approve'] },
  { module: 'Claims',        perms: ['claims:view','claims:create','claims:update','claims:approve'] },
  { module: 'Finance',       perms: ['finance:view','finance:create','finance:update','finance:approve'] },
  { module: 'Reinsurance',   perms: ['reinsurance:view','reinsurance:create','reinsurance:update','reinsurance:approve'] },
  { module: 'Audit',         perms: ['audit:view'] },
];

const schema = z.object({
  name:        z.string().min(2, 'Required'),
  permissions: z.array(z.string()).min(1, 'Select at least one permission'),
});
type FormValues = z.infer<typeof schema>;

interface Props {
  open: boolean; onOpenChange: (v: boolean) => void;
  group: AccessGroupDto | null; onSuccess: () => void;
}

export default function AccessGroupSheet({ open, onOpenChange, group, onSuccess }: Props) {
  const form = useForm<FormValues>({
    resolver:      zodResolver(schema),
    defaultValues: { name: '', permissions: [] },
  });

  useEffect(() => {
    form.reset(group ? { name: group.name, permissions: group.permissions } : { name: '', permissions: [] });
  }, [group, form]);

  async function onSubmit(values: FormValues) {
    console.log(group ? 'Update group' : 'Create group', values);
    onSuccess();
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{group ? 'Edit Access Group' : 'New Access Group'}</SheetTitle>
          <SheetDescription>Set the name and which permissions members of this group have.</SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">
            <FormField control={form.control} name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Group Name</FormLabel>
                  <FormControl><Input placeholder="e.g. Senior Underwriter" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField control={form.control} name="permissions"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Permissions</FormLabel>
                  <div className="space-y-4 rounded-lg border p-4">
                    {ALL_PERMISSIONS.map((group) => (
                      <FormSection key={group.module} title={group.module}>
                        <div className="grid grid-cols-2 gap-2">
                          {group.perms.map((perm) => (
                            <div key={perm} className="flex items-center gap-2">
                              <Checkbox
                                id={perm}
                                checked={field.value.includes(perm)}
                                onCheckedChange={(checked) => {
                                  field.onChange(
                                    checked
                                      ? [...field.value, perm]
                                      : field.value.filter((p) => p !== perm),
                                  );
                                }}
                              />
                              <label htmlFor={perm} className="text-xs text-foreground cursor-pointer">
                                {perm.split(':')[1]}
                              </label>
                            </div>
                          ))}
                        </div>
                      </FormSection>
                    ))}
                  </div>
                  <FormMessage />
                </FormItem>
              )}
            />

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? 'Saving…' : group ? 'Save Changes' : 'Create Group'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
