import { useRef, useState } from 'react';
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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, type BrokerDto } from '@cia/api-client';

const EXPIRY_TYPES = ['DRIVERS_LICENSE', 'PASSPORT'] as const;
const MAX_FILE_BYTES = 5 * 1024 * 1024;
const ALLOWED_MIME = ['image/jpeg', 'image/jpg', 'image/png'];

const schema = z.object({
  firstName:     z.string().min(2, 'Required'),
  lastName:      z.string().min(2, 'Required'),
  email:         z.string().email('Invalid email'),
  phone:         z.string().min(7, 'Required'),
  dateOfBirth:   z.string().min(1, 'Required'),
  idType:        z.enum(['NIN', 'VOTERS_CARD', 'DRIVERS_LICENSE', 'PASSPORT']),
  idNumber:      z.string().min(5, 'Required'),
  idExpiryDate:  z.string().optional(),
  address:       z.string().min(10, 'Required'),
  occupation:    z.string().optional(),
  brokerEnabled: z.boolean(),
  brokerId:      z.string().optional(),
}).superRefine((data, ctx) => {
  if (EXPIRY_TYPES.includes(data.idType as typeof EXPIRY_TYPES[number])) {
    if (!data.idExpiryDate) {
      ctx.addIssue({ code: 'custom', path: ['idExpiryDate'], message: 'Expiry date is required for this ID type' });
    } else if (new Date(data.idExpiryDate) < new Date(new Date().toDateString())) {
      ctx.addIssue({ code: 'custom', path: ['idExpiryDate'], message: 'ID document has expired — please provide a valid document' });
    }
  }
});
type FormValues = z.infer<typeof schema>;

const ID_TYPES = [
  { value: 'NIN',             label: 'National ID (NIN)' },
  { value: 'VOTERS_CARD',     label: "Voter's Card" },
  { value: 'DRIVERS_LICENSE', label: "Driver's License" },
  { value: 'PASSPORT',        label: 'International Passport' },
];


interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function IndividualOnboardingSheet({ open, onOpenChange, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [idFile, setIdFile]       = useState<File | null>(null);
  const [fileError, setFileError] = useState<string>('');

  const brokersQuery = useQuery<BrokerDto[]>({
    queryKey: ['setup', 'brokers'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: BrokerDto[] }>('/api/v1/setup/brokers');
      return res.data.data;
    },
    enabled: open,
  });
  const brokers = brokersQuery.data ?? [];

  const form = useForm<FormValues>({
    resolver:      zodResolver(schema),
    defaultValues: {
      firstName: '', lastName: '', email: '', phone: '', dateOfBirth: '',
      idType: 'NIN', idNumber: '', idExpiryDate: '', address: '',
      occupation: '', brokerEnabled: false, brokerId: '',
    },
  });

  const idType = form.watch('idType');
  const needsExpiry   = EXPIRY_TYPES.includes(idType as typeof EXPIRY_TYPES[number]);
  const brokerEnabled = form.watch('brokerEnabled');

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!ALLOWED_MIME.includes(file.type)) {
      setFileError('Only JPG and PNG files are accepted.');
      setIdFile(null);
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      setFileError('File must be 5 MB or smaller.');
      setIdFile(null);
      return;
    }
    setFileError('');
    setIdFile(file);
  }

  const onboard = useMutation({
    mutationFn: async (values: FormValues) => {
      const fd = new FormData();
      fd.append('firstName',   values.firstName);
      fd.append('lastName',    values.lastName);
      fd.append('email',       values.email);
      fd.append('phone',       values.phone);
      fd.append('dateOfBirth', values.dateOfBirth);
      fd.append('idType',      values.idType);
      fd.append('idNumber',    values.idNumber);
      if (values.idExpiryDate) fd.append('idExpiryDate', values.idExpiryDate);
      fd.append('address',     values.address);
      if (values.occupation)   fd.append('occupation', values.occupation);
      if (values.brokerEnabled && values.brokerId) fd.append('brokerId', values.brokerId);
      fd.append('idDocument',  idFile!);
      return apiClient.post('/api/v1/customers/individual', fd);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      onSuccess();
      form.reset();
      setIdFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
    },
  });

  async function onSubmit(values: FormValues) {
    if (!idFile) {
      setFileError('Please upload a copy of the ID document.');
      return;
    }
    onboard.mutate(values);
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

            {/* KYC Identity Document */}
            <p className="text-sm font-semibold text-foreground">KYC Identity Document</p>

            <FormRow>
              <FormField control={form.control} name="idType"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>ID Type</FormLabel>
                    <Select
                      onValueChange={(v) => { field.onChange(v); form.setValue('idExpiryDate', ''); }}
                      value={field.value}
                    >
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

            {/* Expiry date — Driver's Licence and Passport only */}
            {needsExpiry && (
              <FormField control={form.control} name="idExpiryDate"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      ID Expiry Date <span className="text-destructive">*</span>
                    </FormLabel>
                    <FormControl>
                      <Input type="date" min={new Date().toISOString().slice(0, 10)} {...field} />
                    </FormControl>
                    <FormDescription className="text-xs">
                      Must not be expired as of today.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            {/* ID document upload */}
            <FormItem>
              <FormLabel>
                Upload ID Document <span className="text-destructive">*</span>
              </FormLabel>
              <div
                className="flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-border bg-secondary/30 p-5 gap-2 cursor-pointer hover:border-primary/50 transition-colors"
                onClick={() => fileInputRef.current?.click()}
              >
                {idFile ? (
                  <>
                    <p className="text-sm font-medium text-foreground truncate max-w-full px-2">{idFile.name}</p>
                    <p className="text-xs text-muted-foreground">{(idFile.size / 1024).toFixed(0)} KB</p>
                    <Button
                      type="button" variant="ghost" size="sm" className="text-xs h-7 text-destructive"
                      onClick={e => { e.stopPropagation(); setIdFile(null); if (fileInputRef.current) fileInputRef.current.value = ''; }}
                    >
                      Remove
                    </Button>
                  </>
                ) : (
                  <>
                    <p className="text-sm text-muted-foreground">Click to upload JPG or PNG</p>
                    <p className="text-xs text-muted-foreground">Max 5 MB</p>
                  </>
                )}
              </div>
              <input
                ref={fileInputRef}
                type="file"
                accept=".jpg,.jpeg,.png"
                className="hidden"
                onChange={handleFileChange}
              />
              {fileError && <p className="text-xs text-destructive mt-1">{fileError}</p>}
            </FormItem>

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
                      <SelectContent>{brokers.map(b => <SelectItem key={b.id} value={b.id}>{b.name}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            {onboard.isError && (
              <p className="text-xs text-destructive rounded border border-destructive/30 bg-destructive/10 px-3 py-2">
                Failed to onboard customer. Please check the details and try again.
              </p>
            )}

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={onboard.isPending}>
                {onboard.isPending ? 'Saving…' : 'Onboard Customer'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
