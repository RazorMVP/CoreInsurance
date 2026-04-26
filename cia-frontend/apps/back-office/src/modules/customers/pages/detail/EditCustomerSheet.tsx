import { useEffect, useRef, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import {
  Button, Form, FormControl, FormDescription, FormField, FormItem,
  FormLabel, FormMessage, FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Separator, Sheet, SheetContent, SheetDescription, SheetFooter,
  SheetHeader, SheetTitle, Textarea,
} from '@cia/ui';

const KYC_REASONS = [
  'Document expired',
  'Incorrect details submitted',
  'Name mismatch',
  'Customer request',
  'ID type change',
  'Other',
] as const;

const ID_TYPES = [
  { value: 'NIN',             label: 'National ID (NIN)' },
  { value: 'VOTERS_CARD',     label: "Voter's Card" },
  { value: 'DRIVERS_LICENSE', label: "Driver's License" },
  { value: 'PASSPORT',        label: 'International Passport' },
];

const EXPIRY_TYPES = ['DRIVERS_LICENSE', 'PASSPORT'];

const MAX_FILE_BYTES = 5 * 1024 * 1024;
const ALLOWED_MIME   = ['image/jpeg', 'image/jpg', 'image/png'];

const schema = z.object({
  // contact
  email:         z.string().email('Invalid email').or(z.literal('')),
  phone:         z.string().min(7, 'Required').or(z.literal('')),
  address:       z.string().min(5, 'Required').or(z.literal('')),
  contactPerson: z.string().optional(), // corporate only
  brokerId:      z.string().optional(),

  // KYC
  idType:       z.string().optional(),
  idNumber:     z.string().optional(),
  idExpiryDate: z.string().optional(),

  // KYC reason — required only when KYC fields change (enforced conditionally)
  kycUpdateReason: z.string().optional(),
  kycUpdateNotes:  z.string().optional(),
}).superRefine((data, ctx) => {
  if (data.idExpiryDate && new Date(data.idExpiryDate) < new Date(new Date().toDateString())) {
    ctx.addIssue({ code: 'custom', path: ['idExpiryDate'], message: 'ID document has expired — provide a valid document' });
  }
});

type FormValues = z.infer<typeof schema>;

export interface CustomerSnapshot {
  id: string;
  customerType: 'INDIVIDUAL' | 'CORPORATE';
  email: string;
  phone: string;
  address: string;
  contactPerson?: string;
  brokerName?: string;
  brokerId?: string;
  idType?: string;
  idNumber?: string;
  idExpiryDate?: string;
}

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  customer: CustomerSnapshot;
  onSuccess: () => void;
}

const MOCK_BROKERS = [
  { id: '__none__',  name: 'Direct (no broker)' },
  { id: 'b1',        name: 'Leadway Brokers Ltd' },
  { id: 'b2',        name: 'Stanbic IBTC Brokers' },
];

export default function EditCustomerSheet({ open, onOpenChange, customer, onSuccess }: Props) {
  const queryClient  = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [idFile, setIdFile]       = useState<File | null>(null);
  const [fileError, setFileError] = useState('');

  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: {
      email:         customer.email,
      phone:         customer.phone,
      address:       customer.address,
      contactPerson: customer.contactPerson ?? '',
      brokerId:      customer.brokerId ?? '__none__',
      idType:        customer.idType ?? '',
      idNumber:      customer.idNumber ?? '',
      idExpiryDate:  customer.idExpiryDate ?? '',
      kycUpdateReason: '',
      kycUpdateNotes:  '',
    },
  });

  // Reset form + file state when the sheet opens for a different customer
  useEffect(() => {
    if (open) {
      form.reset({
        email:           customer.email,
        phone:           customer.phone,
        address:         customer.address,
        contactPerson:   customer.contactPerson ?? '',
        brokerId:        customer.brokerId ?? '__none__',
        idType:          customer.idType ?? '',
        idNumber:        customer.idNumber ?? '',
        idExpiryDate:    customer.idExpiryDate ?? '',
        kycUpdateReason: '',
        kycUpdateNotes:  '',
      });
      setIdFile(null);
      setFileError('');
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }, [open, customer.id]);

  const watched = useWatch({ control: form.control, name: ['idType', 'idNumber', 'idExpiryDate'] });
  const kycChanged =
    watched[0] !== (customer.idType  ?? '') ||
    watched[1] !== (customer.idNumber ?? '') ||
    watched[2] !== (customer.idExpiryDate ?? '') ||
    idFile !== null;

  const currentIdType = form.watch('idType');
  const needsExpiry   = EXPIRY_TYPES.includes(currentIdType ?? '');

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

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const fd = new FormData();
      fd.append('email',   values.email);
      fd.append('phone',   values.phone);
      fd.append('address', values.address);
      if (customer.customerType === 'CORPORATE' && values.contactPerson) {
        fd.append('contactPerson', values.contactPerson);
      }
      const broker = values.brokerId === '__none__' ? '' : (values.brokerId ?? '');
      if (broker) fd.append('brokerId', broker);

      if (kycChanged) {
        if (values.idType)       fd.append('idType',       values.idType);
        if (values.idNumber)     fd.append('idNumber',     values.idNumber);
        if (values.idExpiryDate) fd.append('idExpiryDate', values.idExpiryDate);
        if (values.kycUpdateReason) fd.append('kycUpdateReason', values.kycUpdateReason);
        if (values.kycUpdateNotes)  fd.append('kycUpdateNotes',  values.kycUpdateNotes);
        if (idFile)              fd.append('idDocument', idFile);
      }

      return apiClient.put(`/api/v1/customers/${customer.id}`, fd);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      queryClient.invalidateQueries({ queryKey: ['customer', customer.id] });
      onSuccess();
      onOpenChange(false);
    },
  });

  async function onSubmit(values: FormValues) {
    if (kycChanged && !values.kycUpdateReason) {
      form.setError('kycUpdateReason', { message: 'Reason is required when KYC details are changed' });
      return;
    }
    save.mutate(values);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Edit Customer</SheetTitle>
          <SheetDescription>
            Update contact details or KYC information. KYC changes will trigger automatic re-verification.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">

            {/* ── Contact details ── */}
            <p className="text-sm font-semibold text-foreground">Contact Details</p>

            <FormRow>
              <FormField control={form.control} name="email"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Email</FormLabel>
                    <FormControl><Input type="email" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="phone"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Phone</FormLabel>
                    <FormControl><Input {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            <FormField control={form.control} name="address"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Address</FormLabel>
                  <FormControl><Input placeholder="House No, Street, City, State" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            {customer.customerType === 'CORPORATE' && (
              <FormField control={form.control} name="contactPerson"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Contact Person</FormLabel>
                    <FormControl><Input placeholder="Primary contact name" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            <FormField control={form.control} name="brokerId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Channel</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                    <SelectContent>
                      {MOCK_BROKERS.map(b => (
                        <SelectItem key={b.id} value={b.id}>{b.name}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Separator />

            {/* ── KYC details ── */}
            <p className="text-sm font-semibold text-foreground">KYC Identity Document</p>

            <FormRow>
              <FormField control={form.control} name="idType"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>ID Type</FormLabel>
                    <Select
                      onValueChange={v => { field.onChange(v); form.setValue('idExpiryDate', ''); }}
                      value={field.value}
                    >
                      <FormControl><SelectTrigger><SelectValue placeholder="Select ID type" /></SelectTrigger></FormControl>
                      <SelectContent>
                        {ID_TYPES.map(t => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="idNumber"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>ID Number</FormLabel>
                    <FormControl><Input placeholder="Enter ID number" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            {needsExpiry && (
              <FormField control={form.control} name="idExpiryDate"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>ID Expiry Date <span className="text-destructive">*</span></FormLabel>
                    <FormControl>
                      <Input type="date" min={new Date().toISOString().slice(0, 10)} {...field} />
                    </FormControl>
                    <FormDescription className="text-xs">Must not be expired as of today.</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            {/* ID document upload */}
            <FormItem>
              <FormLabel>Upload New ID Document</FormLabel>
              <div
                className="flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-border bg-secondary/30 p-5 gap-2 cursor-pointer hover:border-primary/50 transition-colors"
                onClick={() => fileInputRef.current?.click()}
              >
                {idFile ? (
                  <>
                    <p className="text-sm font-medium text-foreground truncate max-w-full px-2">{idFile.name}</p>
                    <p className="text-xs text-muted-foreground">{(idFile.size / 1024).toFixed(0)} KB</p>
                    <Button type="button" variant="ghost" size="sm" className="text-xs h-7 text-destructive"
                      onClick={e => { e.stopPropagation(); setIdFile(null); if (fileInputRef.current) fileInputRef.current.value = ''; }}>
                      Remove
                    </Button>
                  </>
                ) : (
                  <>
                    <p className="text-sm text-muted-foreground">Click to replace ID document (JPG or PNG)</p>
                    <p className="text-xs text-muted-foreground">Max 5 MB · leave empty to keep existing</p>
                  </>
                )}
              </div>
              <input ref={fileInputRef} type="file" accept=".jpg,.jpeg,.png" className="hidden" onChange={handleFileChange} />
              {fileError && <p className="text-xs text-destructive mt-1">{fileError}</p>}
            </FormItem>

            {/* ── KYC reason — shown only when KYC fields changed ── */}
            {kycChanged && (
              <>
                <Separator />
                <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 space-y-4">
                  <p className="text-xs font-semibold text-amber-800">
                    KYC details have changed — a reason is required.
                  </p>

                  <FormField control={form.control} name="kycUpdateReason"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Reason for KYC Update <span className="text-destructive">*</span></FormLabel>
                        <Select onValueChange={field.onChange} value={field.value}>
                          <FormControl><SelectTrigger><SelectValue placeholder="Select reason…" /></SelectTrigger></FormControl>
                          <SelectContent>
                            {KYC_REASONS.map(r => <SelectItem key={r} value={r}>{r}</SelectItem>)}
                          </SelectContent>
                        </Select>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField control={form.control} name="kycUpdateNotes"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Additional Notes (optional)</FormLabel>
                        <FormControl>
                          <Textarea placeholder="Any additional context…" rows={2} className="resize-none" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              </>
            )}

            {save.isError && (
              <p className="text-xs text-destructive rounded border border-destructive/30 bg-destructive/10 px-3 py-2">
                Failed to save changes. Please check the details and try again.
              </p>
            )}

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : 'Save Changes'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
