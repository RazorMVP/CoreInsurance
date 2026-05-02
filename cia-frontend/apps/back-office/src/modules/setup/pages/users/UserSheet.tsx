import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { apiClient, type UserDto, type AccessGroupDto } from '@cia/api-client';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';

const schema = z.object({
  firstName:     z.string().min(1, 'Required'),
  lastName:      z.string().min(1, 'Required'),
  email:         z.string().email('Invalid email'),
  accessGroupId: z.string().min(1, 'Required'),
  status:        z.enum(['ACTIVE', 'INACTIVE']),
});

type FormValues = z.infer<typeof schema>;

interface UserSheetProps {
  open:          boolean;
  onOpenChange:  (open: boolean) => void;
  user:          UserDto | null;
  onSuccess:     () => void;
}

export default function UserSheet({ open, onOpenChange, user, onSuccess }: UserSheetProps) {
  const isEditing = !!user;
  const queryClient = useQueryClient();

  const groupsQuery = useQuery<AccessGroupDto[]>({
    queryKey: ['setup', 'access-groups'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: AccessGroupDto[] }>('/api/v1/setup/access-groups');
      return res.data.data;
    },
    enabled: open,
  });
  const groups = groupsQuery.data ?? [];

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

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      if (isEditing && user) {
        const res = await apiClient.put<{ data: UserDto }>(
          `/api/v1/setup/users/${user.id}`, values,
        );
        return res.data.data;
      }
      const res = await apiClient.post<{ data: UserDto }>(
        '/api/v1/setup/users', values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['setup', 'users'] });
      onSuccess();
    },
  });

  function onSubmit(values: FormValues) {
    save.mutate(values);
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
                      {groups.map((g) => (
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
