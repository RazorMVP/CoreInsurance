import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Checkbox, Form, FormControl, FormDescription, FormField,
  FormItem, FormLabel, FormMessage, FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useFieldArray, useForm } from 'react-hook-form';
import { z } from 'zod';

const directorSchema = z.object({
  fullName:  z.string().min(2, 'Required'),
  idType:    z.enum(['NIN', 'VOTERS_CARD', 'DRIVERS_LICENSE', 'PASSPORT']),
  idNumber:  z.string().min(5, 'Required'),
});

const schema = z.object({
  companyName:   z.string().min(2, 'Required'),
  rcNumber:      z.string().min(6, 'Required'),
  email:         z.string().email('Invalid email'),
  phone:         z.string().min(7, 'Required'),
  address:       z.string().min(10, 'Required'),
  directors:     z.array(directorSchema).min(1, 'At least one director required'),
  brokerEnabled: z.boolean(),
  brokerId:      z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

const ID_TYPES = [
  { value: 'NIN',             label: 'NIN' },
  { value: 'VOTERS_CARD',     label: "Voter's Card" },
  { value: 'DRIVERS_LICENSE', label: "Driver's License" },
  { value: 'PASSPORT',        label: 'Passport' },
];

const mockBrokers = [
  { id: 'b1', name: 'Leadway Brokers Ltd' },
  { id: 'b2', name: 'Stanbic IBTC Brokers' },
];

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function CorporateOnboardingSheet({ open, onOpenChange, onSuccess }: Props) {
  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: {
      companyName: '', rcNumber: '', email: '', phone: '', address: '',
      directors:   [{ fullName: '', idType: 'NIN', idNumber: '' }],
      brokerEnabled: false, brokerId: '',
    },
  });

  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'directors' });
  const brokerEnabled = form.watch('brokerEnabled');

  async function onSubmit(values: FormValues) {
    console.log('Onboard corporate customer', values);
    // TODO: POST /api/v1/customers/corporate
    onSuccess();
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Corporate Customer Onboarding</SheetTitle>
          <SheetDescription>
            Enter company details and at least one director. KYC verification (RC number + director IDs) is triggered automatically.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">
            {/* Company details */}
            <FormField control={form.control} name="companyName"
              render={({ field }) => (<FormItem><FormLabel>Company Name</FormLabel><FormControl><Input placeholder="e.g. Alaba Trading Co. Ltd" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormRow>
              <FormField control={form.control} name="rcNumber"
                render={({ field }) => (<FormItem><FormLabel>RC Number</FormLabel><FormControl><Input placeholder="e.g. RC123456" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="phone"
                render={({ field }) => (<FormItem><FormLabel>Phone</FormLabel><FormControl><Input placeholder="+234 701 000 0000" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormField control={form.control} name="email"
              render={({ field }) => (<FormItem><FormLabel>Business Email</FormLabel><FormControl><Input type="email" placeholder="info@company.ng" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <FormField control={form.control} name="address"
              render={({ field }) => (<FormItem><FormLabel>Registered Address</FormLabel><FormControl><Input placeholder="House No, Street, City, State" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <Separator />

            {/* Directors */}
            <div className="space-y-3">
              <p className="text-sm font-semibold text-foreground">Directors (KYC)</p>
              {fields.map((f, i) => (
                <div key={f.id} className="rounded-lg border p-4 space-y-3">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-medium text-muted-foreground">Director {i + 1}</p>
                    {fields.length > 1 && (
                      <Button type="button" variant="ghost" size="sm" onClick={() => remove(i)} className="h-7 text-xs text-destructive">
                        Remove
                      </Button>
                    )}
                  </div>
                  <FormField control={form.control} name={`directors.${i}.fullName`}
                    render={({ field }) => (<FormItem><FormLabel>Full Name</FormLabel><FormControl><Input placeholder="Director full name" {...field} /></FormControl><FormMessage /></FormItem>)} />
                  <FormRow>
                    <FormField control={form.control} name={`directors.${i}.idType`}
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>ID Type</FormLabel>
                          <Select onValueChange={field.onChange} value={field.value}>
                            <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                            <SelectContent>{ID_TYPES.map(t => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}</SelectContent>
                          </Select>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                    <FormField control={form.control} name={`directors.${i}.idNumber`}
                      render={({ field }) => (<FormItem><FormLabel>ID Number</FormLabel><FormControl><Input placeholder="ID number" {...field} /></FormControl><FormMessage /></FormItem>)} />
                  </FormRow>
                </div>
              ))}
              <Button type="button" variant="outline" size="sm"
                onClick={() => append({ fullName: '', idType: 'NIN', idNumber: '' })}>
                + Add Director
              </Button>
            </div>

            <Separator />

            {/* Broker-enabled */}
            <FormField control={form.control} name="brokerEnabled"
              render={({ field }) => (
                <FormItem className="flex items-start gap-3 rounded-lg border p-4">
                  <FormControl>
                    <Checkbox checked={field.value} onCheckedChange={field.onChange} />
                  </FormControl>
                  <div>
                    <FormLabel className="cursor-pointer">Broker-enabled onboarding</FormLabel>
                    <FormDescription className="text-xs">This company is being onboarded through a broker.</FormDescription>
                  </div>
                </FormItem>
              )}
            />

            {brokerEnabled && (
              <FormField control={form.control} name="brokerId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Broker</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select broker" /></SelectTrigger></FormControl>
                      <SelectContent>{mockBrokers.map(b => <SelectItem key={b.id} value={b.id}>{b.name}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? 'Saving…' : 'Onboard Company'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
