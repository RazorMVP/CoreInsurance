import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import type { UserDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

const schema = z.object({
  firstName:     z.string().min(1, 'Required'),
  lastName:      z.string().min(1, 'Required'),
  email:         z.string().email('Invalid email'),
  accessGroupId: z.string().min(1, 'Required'),
  status:        z.enum(['ACTIVE', 'INACTIVE']),
});

type FormValues = z.infer<typeof schema>;

// Placeholder — replace with useAccessGroups() hook
const mockGroups = [
  { id: 'ag1', name: 'System Admin' },
  { id: 'ag2', name: 'Underwriter' },
  { id: 'ag3', name: 'Claims Officer' },
  { id: 'ag4', name: 'Finance Officer' },
  { id: 'ag5', name: 'System Auditor' },
];

interface UserSheetProps {
  open:          boolean;
  onOpenChange:  (open: boolean) => void;
  user:          UserDto | null;
  onSuccess:     () => void;
}

export default function UserSheet({ open, onOpenChange, user, onSuccess }: UserSheetProps) {
  const isEditing = !!user;

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      firstName:     '',
      lastName:      '',
      email:         '',
      accessGroupId: '',
      status:        'ACTIVE',
    },
  });

  useEffect(() => {
    if (user) {
      form.reset({
        firstName:     user.firstName,
        lastName:      user.lastName,
        email:         user.email,
        accessGroupId: user.accessGroupId,
        status:        user.status === 'LOCKED' ? 'ACTIVE' : user.status,
      });
    } else {
      form.reset({ firstName: '', lastName: '', email: '', accessGroupId: '', status: 'ACTIVE' });
    }
  }, [user, form]);

  async function onSubmit(values: FormValues) {
    console.log(isEditing ? 'Update user' : 'Create user', values);
    // TODO: useCreate / useUpdate hooks
    onSuccess();
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-md overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{isEditing ? 'Edit User' : 'Add User'}</SheetTitle>
          <SheetDescription>
            {isEditing
              ? "Update this user's details and access group."
              : "Create a new system user. They'll receive a welcome email with login instructions."}
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-4">
            <FormRow>
              <FormField
                control={form.control}
                name="firstName"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>First Name</FormLabel>
                    <FormControl><Input placeholder="Chidi" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="lastName"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Last Name</FormLabel>
                    <FormControl><Input placeholder="Okafor" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            <FormField
              control={form.control}
              name="email"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Email Address</FormLabel>
                  <FormControl>
                    <Input type="email" placeholder="user@company.ng" disabled={isEditing} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="accessGroupId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Access Group</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl>
                      <SelectTrigger><SelectValue placeholder="Select a group" /></SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {mockGroups.map((g) => (
                        <SelectItem key={g.id} value={g.id}>{g.name}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            {isEditing && (
              <FormField
                control={form.control}
                name="status"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Status</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        <SelectItem value="ACTIVE">Active</SelectItem>
                        <SelectItem value="INACTIVE">Inactive</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            <SheetFooter className="pt-4">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? 'Saving…' : isEditing ? 'Save Changes' : 'Create User'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
