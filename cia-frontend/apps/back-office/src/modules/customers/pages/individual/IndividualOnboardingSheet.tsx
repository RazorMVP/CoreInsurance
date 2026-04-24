import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Checkbox, Form, FormControl, FormDescription, FormField,
  FormItem, FormLabel, FormMessage, FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  Separator,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

const schema = z.object({
  firstName:    z.string().min(2, 'Required'),
  lastName:     z.string().min(2, 'Required'),
  email:        z.string().email('Invalid email'),
  phone:        z.string().min(7, 'Required'),
  dateOfBirth:  z.string().min(1, 'Required'),
  idType:       z.enum(['NIN', 'VOTERS_CARD', 'DRIVERS_LICENSE', 'PASSPORT']),
  idNumber:     z.string().min(5, 'Required'),
  address:      z.string().min(10, 'Required'),
  occupation:   z.string().optional(),
  brokerEnabled:z.boolean(),
  brokerId:     z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

const ID_TYPES = [
  { value: 'NIN',             label: 'National ID (NIN)' },
  { value: 'VOTERS_CARD',     label: "Voter's Card" },
  { value: 'DRIVERS_LICENSE', label: "Driver's License" },
  { value: 'PASSPORT',        label: 'International Passport' },
];

const mockBrokers = [
  { id: 'b1', name: 'Leadway Brokers Ltd' },
  { id: 'b2', name: 'Stanbic IBTC Brokers' },
];

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function IndividualOnboardingSheet({ open, onOpenChange, onSuccess }: Props) {
  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: { firstName: '', lastName: '', email: '', phone: '', dateOfBirth: '', idType: 'NIN', idNumber: '', address: '', occupation: '', brokerEnabled: false, brokerId: '' },
  });

  const brokerEnabled = form.watch('brokerEnabled');

  async function onSubmit(values: FormValues) {
    console.log('Onboard individual customer', values);
    // TODO: POST /api/v1/customers/individual
    onSuccess();
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Individual Customer Onboarding</SheetTitle>
          <SheetDescription>
            Enter the customer's personal details. KYC verification will be triggered automatically after saving.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">
            {/* Personal details */}
            <FormRow>
              <FormField control={form.control} name="firstName"
                render={({ field }) => (<FormItem><FormLabel>First Name</FormLabel><FormControl><Input placeholder="Chioma" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="lastName"
                render={({ field }) => (<FormItem><FormLabel>Last Name</FormLabel><FormControl><Input placeholder="Okafor" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormRow>
              <FormField control={form.control} name="email"
                render={({ field }) => (<FormItem><FormLabel>Email</FormLabel><FormControl><Input type="email" placeholder="chioma@email.ng" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="phone"
                render={({ field }) => (<FormItem><FormLabel>Phone</FormLabel><FormControl><Input placeholder="+234 803 000 0000" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormRow>
              <FormField control={form.control} name="dateOfBirth"
                render={({ field }) => (<FormItem><FormLabel>Date of Birth</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>)} />
              <FormField control={form.control} name="occupation"
                render={({ field }) => (<FormItem><FormLabel>Occupation (optional)</FormLabel><FormControl><Input placeholder="e.g. Engineer" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

            <FormField control={form.control} name="address"
              render={({ field }) => (<FormItem><FormLabel>Residential Address</FormLabel><FormControl><Input placeholder="House No, Street, City, State" {...field} /></FormControl><FormMessage /></FormItem>)} />

            <Separator />

            {/* KYC */}
            <p className="text-sm font-semibold text-foreground">KYC Identity Document</p>
            <FormRow>
              <FormField control={form.control} name="idType"
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
              <FormField control={form.control} name="idNumber"
                render={({ field }) => (<FormItem><FormLabel>ID Number</FormLabel><FormControl><Input placeholder="Enter ID number" {...field} /></FormControl><FormMessage /></FormItem>)} />
            </FormRow>

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
                    <FormDescription className="text-xs">This customer is being onboarded through a broker.</FormDescription>
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
                {form.formState.isSubmitting ? 'Saving…' : 'Onboard Customer'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
