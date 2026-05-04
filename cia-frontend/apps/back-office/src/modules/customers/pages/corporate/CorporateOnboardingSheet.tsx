import { useMemo, useRef, useState } from 'react';
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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, type BrokerDto } from '@cia/api-client';

const EXPIRY_TYPES = ['DRIVERS_LICENSE', 'PASSPORT'] as const;
const MAX_FILE_BYTES = 5 * 1024 * 1024;
const ALLOWED_MIME = ['image/jpeg', 'image/jpg', 'image/png'];

const directorSchema = z.object({
  fullName:     z.string().min(2, 'Required'),
  idType:       z.enum(['NIN', 'VOTERS_CARD', 'DRIVERS_LICENSE', 'PASSPORT']),
  idNumber:     z.string().min(5, 'Required'),
  idExpiryDate: z.string().optional(),
}).superRefine((data, ctx) => {
  if (EXPIRY_TYPES.includes(data.idType as typeof EXPIRY_TYPES[number])) {
    if (!data.idExpiryDate) {
      ctx.addIssue({ code: 'custom', path: ['idExpiryDate'], message: 'Expiry date required for this ID type' });
      return;
    }
    const startOfToday = new Date();
    startOfToday.setHours(0, 0, 0, 0);
    if (new Date(data.idExpiryDate) < startOfToday) {
      ctx.addIssue({ code: 'custom', path: ['idExpiryDate'], message: 'ID document has expired — provide a valid document' });
    }
  }
});

const schema = z.object({
  companyName:   z.string().min(2, 'Required'),
  rcNumber:      z.string().min(6, 'Required'),
  cacIssuedDate: z.string().min(1, 'CAC issued date is required'),
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


function validateFile(file: File): string | null {
  if (!ALLOWED_MIME.includes(file.type)) return 'Only JPG and PNG files are accepted.';
  if (file.size > MAX_FILE_BYTES) return 'File must be 5 MB or smaller.';
  return null;
}

interface Props { open: boolean; onOpenChange: (v: boolean) => void; onSuccess: () => void; }

export default function CorporateOnboardingSheet({ open, onOpenChange, onSuccess }: Props) {
  const queryClient = useQueryClient();

  const brokersQuery = useQuery<BrokerDto[]>({
    queryKey: ['setup', 'brokers'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: BrokerDto[] }>('/api/v1/setup/brokers');
      return res.data.data;
    },
    enabled: open,
  });
  // Memoised so SelectItems aren't re-created on every parent render.
  const brokers = useMemo(() => brokersQuery.data ?? [], [brokersQuery.data]);

  // CAC certificate
  const cacFileRef   = useRef<HTMLInputElement>(null);
  const [cacFile, setCacFile]       = useState<File | null>(null);
  const [cacFileError, setCacFileError] = useState<string>('');

  // Per-director ID document files
  const [dirFiles, setDirFiles]       = useState<(File | null)[]>([null]);
  const [dirFileErrors, setDirFileErrors] = useState<string[]>(['']);
  // One ref per director input — stored in a ref array (never inside map())
  const dirFileRefs = useRef<(HTMLInputElement | null)[]>([null]);

  const form = useForm<FormValues>({
    resolver:      zodResolver(schema),
    defaultValues: {
      companyName: '', rcNumber: '', cacIssuedDate: '', email: '', phone: '', address: '',
      directors:   [{ fullName: '', idType: 'NIN', idNumber: '', idExpiryDate: '' }],
      brokerEnabled: false, brokerId: '',
    },
  });

  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'directors' });
  const brokerEnabled = form.watch('brokerEnabled');
  const directors = form.watch('directors');

  function handleCacFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const err = validateFile(file);
    if (err) { setCacFileError(err); setCacFile(null); return; }
    setCacFileError('');
    setCacFile(file);
  }

  function handleDirFile(idx: number, e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const err = validateFile(file);
    const newFiles  = [...dirFiles];
    const newErrors = [...dirFileErrors];
    if (err) { newErrors[idx] = err; newFiles[idx] = null; }
    else     { newErrors[idx] = '';  newFiles[idx] = file; }
    setDirFiles(newFiles);
    setDirFileErrors(newErrors);
  }

  function removeDirFile(idx: number) {
    const newFiles  = [...dirFiles];
    const newErrors = [...dirFileErrors];
    newFiles[idx]  = null;
    newErrors[idx] = '';
    setDirFiles(newFiles);
    setDirFileErrors(newErrors);
  }

  const onboard = useMutation({
    mutationFn: async (values: FormValues) => {
      const fd = new FormData();
      fd.append('companyName',   values.companyName);
      fd.append('rcNumber',      values.rcNumber);
      fd.append('cacIssuedDate', values.cacIssuedDate);
      fd.append('email',         values.email);
      fd.append('phone',         values.phone);
      fd.append('address',       values.address);
      if (values.brokerEnabled && values.brokerId) fd.append('brokerId', values.brokerId);

      // Directors as JSON string (Spring @ModelAttribute will bind list fields)
      values.directors.forEach((d, i) => {
        fd.append(`directors[${i}].fullName`,    d.fullName);
        fd.append(`directors[${i}].idType`,      d.idType);
        fd.append(`directors[${i}].idNumber`,    d.idNumber);
        if (d.idExpiryDate) fd.append(`directors[${i}].idExpiryDate`, d.idExpiryDate);
      });

      fd.append('cacCertificate', cacFile!);
      dirFiles.forEach(f => {
        if (f) fd.append('directorIdDocuments', f);
      });

      return apiClient.post('/api/v1/customers/corporate', fd);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      onSuccess();
      form.reset();
      setCacFile(null);
      setDirFiles([null]);
      setDirFileErrors(['']);
      if (cacFileRef.current) cacFileRef.current.value = '';
    },
  });

  async function onSubmit(values: FormValues) {
    let valid = true;
    if (!cacFile) { setCacFileError('Please upload the CAC certificate.'); valid = false; }
    const newDirErrors: string[] = values.directors.map((_, i) =>
      dirFiles[i] ? '' : "Please upload the director's ID document."
    );
    setDirFileErrors(newDirErrors);
    if (newDirErrors.some(e => e !== '')) valid = false;
    if (!valid) return;
    onboard.mutate(values);
  }

  function addDirector() {
    append({ fullName: '', idType: 'NIN', idNumber: '', idExpiryDate: '' });
    setDirFiles(prev => [...prev, null]);
    setDirFileErrors(prev => [...prev, '']);
  }

  function removeDirector(i: number) {
    remove(i);
    setDirFiles(prev => prev.filter((_, idx) => idx !== i));
    setDirFileErrors(prev => prev.filter((_, idx) => idx !== i));
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

            {/* CAC Certificate */}
            <p className="text-sm font-semibold text-foreground">CAC Certificate</p>

            <FormField control={form.control} name="cacIssuedDate"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Issued Date <span className="text-destructive">*</span></FormLabel>
                  <FormControl><Input type="date" max={new Date().toISOString().slice(0, 10)} {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormItem>
              <FormLabel>Upload CAC Certificate <span className="text-destructive">*</span></FormLabel>
              <div
                className="flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-border bg-secondary/30 p-5 gap-2 cursor-pointer hover:border-primary/50 transition-colors"
                onClick={() => cacFileRef.current?.click()}
              >
                {cacFile ? (
                  <>
                    <p className="text-sm font-medium text-foreground truncate max-w-full px-2">{cacFile.name}</p>
                    <p className="text-xs text-muted-foreground">{(cacFile.size / 1024).toFixed(0)} KB</p>
                    <Button type="button" variant="ghost" size="sm" className="text-xs h-7 text-destructive"
                      onClick={e => { e.stopPropagation(); setCacFile(null); if (cacFileRef.current) cacFileRef.current.value = ''; }}>
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
              <input ref={cacFileRef} type="file" accept=".jpg,.jpeg,.png" className="hidden" onChange={handleCacFile} />
              {cacFileError && <p className="text-xs text-destructive mt-1">{cacFileError}</p>}
            </FormItem>

            <Separator />

            {/* Directors */}
            <div className="space-y-4">
              <p className="text-sm font-semibold text-foreground">Directors (KYC)</p>
              {fields.map((f, i) => {
                const dirIdType = directors[i]?.idType;
                const needsExpiry = EXPIRY_TYPES.includes(dirIdType as typeof EXPIRY_TYPES[number]);

                return (
                  <div key={f.id} className="rounded-lg border p-4 space-y-3">
                    <div className="flex items-center justify-between">
                      <p className="text-xs font-medium text-muted-foreground">Director {i + 1}</p>
                      {fields.length > 1 && (
                        <Button type="button" variant="ghost" size="sm" onClick={() => removeDirector(i)} className="h-7 text-xs text-destructive">
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
                            <Select onValueChange={(v) => { field.onChange(v); form.setValue(`directors.${i}.idExpiryDate`, ''); }} value={field.value}>
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

                    {/* Director expiry date */}
                    {needsExpiry && (
                      <FormField control={form.control} name={`directors.${i}.idExpiryDate`}
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

                    {/* Director ID document upload */}
                    <FormItem>
                      <FormLabel>Upload ID Document <span className="text-destructive">*</span></FormLabel>
                      <div
                        className="flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-border bg-secondary/30 p-4 gap-1.5 cursor-pointer hover:border-primary/50 transition-colors"
                        onClick={() => dirFileRefs.current[i]?.click()}
                      >
                        {dirFiles[i] ? (
                          <>
                            <p className="text-sm font-medium text-foreground truncate max-w-full px-2">{dirFiles[i]!.name}</p>
                            <p className="text-xs text-muted-foreground">{(dirFiles[i]!.size / 1024).toFixed(0)} KB</p>
                            <Button type="button" variant="ghost" size="sm" className="text-xs h-7 text-destructive"
                              onClick={e => { e.stopPropagation(); removeDirFile(i); const r = dirFileRefs.current[i]; if (r) r.value = ''; }}>
                              Remove
                            </Button>
                          </>
                        ) : (
                          <>
                            <p className="text-xs text-muted-foreground">Click to upload JPG or PNG</p>
                            <p className="text-xs text-muted-foreground">Max 5 MB</p>
                          </>
                        )}
                      </div>
                      <input ref={el => { dirFileRefs.current[i] = el; }} type="file" accept=".jpg,.jpeg,.png" className="hidden"
                        onChange={(e) => handleDirFile(i, e)} />
                      {dirFileErrors[i] && <p className="text-xs text-destructive mt-1">{dirFileErrors[i]}</p>}
                    </FormItem>
                  </div>
                );
              })}

              <Button type="button" variant="outline" size="sm" onClick={addDirector}>
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
                      <SelectContent>{brokers.map(b => <SelectItem key={b.id} value={b.id}>{b.name}</SelectItem>)}</SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            {onboard.isError && (
              <p className="text-xs text-destructive rounded border border-destructive/30 bg-destructive/10 px-3 py-2">
                Failed to onboard company. Please check the details and try again.
              </p>
            )}

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={onboard.isPending}>
                {onboard.isPending ? 'Saving…' : 'Onboard Company'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
