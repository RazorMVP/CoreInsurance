import { useEffect, useRef, useState } from 'react';
import { useFieldArray, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import {
  Badge, Button, Form, FormControl, FormDescription, FormField, FormItem,
  FormLabel, FormMessage, FormRow, Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Separator, Sheet, SheetContent, SheetDescription, SheetFooter,
  SheetHeader, SheetTitle, Textarea,
} from '@cia/ui';

// ─── Constants ────────────────────────────────────────────────────────────────

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

const MOCK_BROKERS = [
  { id: '__none__', name: 'Direct (no broker)' },
  { id: 'b1',       name: 'Leadway Brokers Ltd' },
  { id: 'b2',       name: 'Stanbic IBTC Brokers' },
];

// ─── Types ────────────────────────────────────────────────────────────────────

export interface DirectorSnapshot {
  id: string;
  firstName: string;
  lastName: string;
  dateOfBirth?: string;
  idType?: string;
  idNumber?: string;
  idExpiryDate?: string;
}

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
  directors?: DirectorSnapshot[];
}

// ─── Schema ───────────────────────────────────────────────────────────────────

const directorSchema = z.object({
  id:      z.string().optional(),
  deleted: z.boolean(),
  firstName:    z.string().min(1, 'Required'),
  lastName:     z.string().min(1, 'Required'),
  dateOfBirth:  z.string().optional(),
  idType:       z.string().optional(),
  idNumber:     z.string().optional(),
  idExpiryDate: z.string().optional(),
  kycUpdateReason: z.string().optional(),
  kycUpdateNotes:  z.string().optional(),
});

const schema = z.object({
  email:         z.string().email('Invalid email').or(z.literal('')),
  phone:         z.string().min(7, 'Required').or(z.literal('')),
  address:       z.string().min(5, 'Required').or(z.literal('')),
  contactPerson: z.string().optional(),
  brokerId:      z.string().optional(),
  idType:        z.string().optional(),
  idNumber:      z.string().optional(),
  idExpiryDate:  z.string().optional(),
  kycUpdateReason: z.string().optional(),
  kycUpdateNotes:  z.string().optional(),
  directors:     z.array(directorSchema).optional(),
}).superRefine((data, ctx) => {
  // Customer-level expiry
  if (data.idExpiryDate && new Date(data.idExpiryDate) < new Date(new Date().toDateString())) {
    ctx.addIssue({ code: 'custom', path: ['idExpiryDate'], message: 'ID document has expired' });
  }
  // Customer-level notes required if reason = Other
  if (data.kycUpdateReason === 'Other' && !data.kycUpdateNotes?.trim()) {
    ctx.addIssue({ code: 'custom', path: ['kycUpdateNotes'], message: 'Notes required when reason is "Other"' });
  }
  // Per-director validations
  data.directors?.forEach((dir, i) => {
    if (dir.deleted) return;
    if (dir.idExpiryDate && new Date(dir.idExpiryDate) < new Date(new Date().toDateString())) {
      ctx.addIssue({ code: 'custom', path: ['directors', i, 'idExpiryDate'], message: 'ID document has expired' });
    }
    if (dir.kycUpdateReason === 'Other' && !dir.kycUpdateNotes?.trim()) {
      ctx.addIssue({ code: 'custom', path: ['directors', i, 'kycUpdateNotes'], message: 'Notes required when reason is "Other"' });
    }
  });
});

type FormValues = z.infer<typeof schema>;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function buildDefaultDirector(snap?: DirectorSnapshot) {
  return {
    id:              snap?.id,
    deleted:         false,
    firstName:       snap?.firstName    ?? '',
    lastName:        snap?.lastName     ?? '',
    dateOfBirth:     snap?.dateOfBirth  ?? '',
    idType:          snap?.idType       ?? '',
    idNumber:        snap?.idNumber     ?? '',
    idExpiryDate:    snap?.idExpiryDate ?? '',
    kycUpdateReason: '',
    kycUpdateNotes:  '',
  };
}

// ─── Component ────────────────────────────────────────────────────────────────

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  customer: CustomerSnapshot;
  onSuccess: () => void;
}

export default function EditCustomerSheet({ open, onOpenChange, customer, onSuccess }: Props) {
  const queryClient = useQueryClient();

  // Customer-level ID document
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [idFile, setIdFile]       = useState<File | null>(null);
  const [fileError, setFileError] = useState('');

  // Per-director documents: array indexed by field-array position
  const dirFileRefs  = useRef<(HTMLInputElement | null)[]>([]);
  const [dirFiles, setDirFiles]         = useState<(File | null)[]>([]);
  const [dirFileErrors, setDirFileErrors] = useState<string[]>([]);

  const form = useForm<FormValues>({
    resolver:      zodResolver(schema) as any,
    defaultValues: buildDefaults(customer),
  });

  const { fields: dirFields, append, update, remove } = useFieldArray({
    control: form.control,
    name:    'directors',
  });

  // Reset when sheet opens for a (potentially different) customer
  useEffect(() => {
    if (open) {
      form.reset(buildDefaults(customer));
      setIdFile(null);
      setFileError('');
      setDirFiles((customer.directors ?? []).map(() => null));
      setDirFileErrors((customer.directors ?? []).map(() => ''));
      if (fileInputRef.current) fileInputRef.current.value = '';
      dirFileRefs.current = (customer.directors ?? []).map(() => null);
    }
  }, [open, customer.id]);

  // ── Watches ──────────────────────────────────────────────────────────────

  const watchedKyc     = useWatch({ control: form.control, name: ['idType', 'idNumber', 'idExpiryDate'] });
  const selectedReason = form.watch('kycUpdateReason');
  const watchedDirs    = form.watch('directors') ?? [];

  const customerKycChanged =
    watchedKyc[0] !== (customer.idType      ?? '') ||
    watchedKyc[1] !== (customer.idNumber    ?? '') ||
    watchedKyc[2] !== (customer.idExpiryDate ?? '') ||
    idFile !== null;

  const currentIdType = form.watch('idType');
  const needsExpiry   = EXPIRY_TYPES.includes(currentIdType ?? '');

  // Count of non-deleted directors
  const activeDirectorCount = watchedDirs.filter(d => !d.deleted).length;
  const isCorporate         = customer.customerType === 'CORPORATE';
  const tooFewDirectors     = isCorporate && activeDirectorCount < 2;

  // Originals keyed by director id for KYC change detection
  const dirOriginals: Record<string, DirectorSnapshot> = {};
  (customer.directors ?? []).forEach(d => { dirOriginals[d.id] = d; });

  function isDirectorKycChanged(dir: (typeof watchedDirs)[number], i: number): boolean {
    if (!dir.id) return false; // new directors always need verification, no "change" concept
    const orig = dirOriginals[dir.id];
    if (!orig) return false;
    if (dirFiles[i]) return true;
    if (dir.idType   !== (orig.idType      ?? '')) return true;
    if (dir.idNumber !== (orig.idNumber    ?? '')) return true;
    if (dir.idExpiryDate !== (orig.idExpiryDate ?? '')) return true;
    return false;
  }

  // ── File handlers ─────────────────────────────────────────────────────────

  function handleCustomerFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!ALLOWED_MIME.includes(file.type)) { setFileError('Only JPG and PNG files are accepted.'); setIdFile(null); return; }
    if (file.size > MAX_FILE_BYTES)        { setFileError('File must be 5 MB or smaller.');        setIdFile(null); return; }
    setFileError('');
    setIdFile(file);
  }

  function handleDirFileChange(e: React.ChangeEvent<HTMLInputElement>, i: number) {
    const file = e.target.files?.[0];
    const errs = [...dirFileErrors];
    const files = [...dirFiles];
    if (!file) return;
    if (!ALLOWED_MIME.includes(file.type)) { errs[i] = 'Only JPG and PNG files are accepted.'; files[i] = null; }
    else if (file.size > MAX_FILE_BYTES)   { errs[i] = 'File must be 5 MB or smaller.';        files[i] = null; }
    else { errs[i] = ''; files[i] = file; }
    setDirFileErrors(errs);
    setDirFiles(files);
  }

  function removeDirFile(i: number) {
    const files = [...dirFiles]; files[i] = null; setDirFiles(files);
    if (dirFileRefs.current[i]) dirFileRefs.current[i]!.value = '';
  }

  // ── Director actions ──────────────────────────────────────────────────────

  function addDirector() {
    append(buildDefaultDirector());
    setDirFiles(f => [...f, null]);
    setDirFileErrors(e => [...e, '']);
  }

  function toggleDeleteDirector(i: number, currentDeleted: boolean) {
    const dir = dirFields[i];
    if (!dir.id) {
      // New director — just remove from list
      remove(i);
      setDirFiles(f => f.filter((_, idx) => idx !== i));
      setDirFileErrors(e => e.filter((_, idx) => idx !== i));
    } else {
      const current = form.getValues(`directors.${i}`) ?? buildDefaultDirector();
      update(i, { ...current, firstName: current.firstName ?? '', lastName: current.lastName ?? '', deleted: !currentDeleted });
    }
  }

  // ── Submit ────────────────────────────────────────────────────────────────

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const fd = new FormData();
      fd.append('email',   values.email);
      fd.append('phone',   values.phone);
      fd.append('address', values.address);
      if (isCorporate && values.contactPerson) fd.append('contactPerson', values.contactPerson);
      const broker = values.brokerId === '__none__' ? '' : (values.brokerId ?? '');
      if (broker) fd.append('brokerId', broker);

      // Customer-level KYC
      if (customerKycChanged) {
        if (values.idType)           fd.append('idType',           values.idType);
        if (values.idNumber)         fd.append('idNumber',         values.idNumber);
        if (values.idExpiryDate)     fd.append('idExpiryDate',     values.idExpiryDate);
        if (values.kycUpdateReason)  fd.append('kycUpdateReason',  values.kycUpdateReason);
        if (values.kycUpdateNotes)   fd.append('kycUpdateNotes',   values.kycUpdateNotes);
        if (idFile)                  fd.append('idDocument', idFile);
      }

      // Directors
      (values.directors ?? []).forEach((dir, i) => {
        if (dir.id)           fd.append(`directors[${i}].id`,              dir.id);
        fd.append(`directors[${i}].deleted`,       String(dir.deleted));
        fd.append(`directors[${i}].firstName`,     dir.firstName);
        fd.append(`directors[${i}].lastName`,      dir.lastName);
        if (dir.dateOfBirth)  fd.append(`directors[${i}].dateOfBirth`,    dir.dateOfBirth);
        if (dir.idType)       fd.append(`directors[${i}].idType`,         dir.idType);
        if (dir.idNumber)     fd.append(`directors[${i}].idNumber`,       dir.idNumber);
        if (dir.idExpiryDate) fd.append(`directors[${i}].idExpiryDate`,   dir.idExpiryDate);
        if (isDirectorKycChanged(dir, i)) {
          if (dir.kycUpdateReason) fd.append(`directors[${i}].kycUpdateReason`, dir.kycUpdateReason);
          if (dir.kycUpdateNotes)  fd.append(`directors[${i}].kycUpdateNotes`,  dir.kycUpdateNotes);
          if (dirFiles[i])         fd.append(`directorDoc_${i}`, dirFiles[i]!);
        }
      });

      return apiClient.put(`/api/v1/customers/${customer.id}`, fd);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      queryClient.invalidateQueries({ queryKey: ['customer', customer.id] });
      onSuccess();
      onOpenChange(false);
    },
  });

  function onSubmit(values: FormValues) {
    if (customerKycChanged && !values.kycUpdateReason) {
      form.setError('kycUpdateReason', { message: 'Reason is required when KYC details are changed' });
      return;
    }
    save.mutate(values);
  }

  // ─────────────────────────────────────────────────────────────────────────

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Edit Customer</SheetTitle>
          <SheetDescription>
            Update contact details or KYC information. KYC changes trigger automatic re-verification.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">

            {/* ── Contact details ── */}
            <p className="text-sm font-semibold text-foreground">Contact Details</p>

            <FormRow>
              <FormField control={form.control} name="email" render={({ field }) => (
                <FormItem><FormLabel>Email</FormLabel><FormControl><Input type="email" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="phone" render={({ field }) => (
                <FormItem><FormLabel>Phone</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>

            <FormField control={form.control} name="address" render={({ field }) => (
              <FormItem><FormLabel>Address</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
            )} />

            {isCorporate && (
              <FormField control={form.control} name="contactPerson" render={({ field }) => (
                <FormItem><FormLabel>Contact Person</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            )}

            <FormField control={form.control} name="brokerId" render={({ field }) => (
              <FormItem>
                <FormLabel>Channel</FormLabel>
                <Select onValueChange={field.onChange} value={field.value}>
                  <FormControl><SelectTrigger><SelectValue /></SelectTrigger></FormControl>
                  <SelectContent>{MOCK_BROKERS.map(b => <SelectItem key={b.id} value={b.id}>{b.name}</SelectItem>)}</SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />

            {/* ── Customer KYC ── */}
            <Separator />
            <p className="text-sm font-semibold text-foreground">KYC Identity Document</p>

            <FormRow>
              <FormField control={form.control} name="idType" render={({ field }) => (
                <FormItem>
                  <FormLabel>ID Type</FormLabel>
                  <Select onValueChange={v => { field.onChange(v); form.setValue('idExpiryDate', ''); }} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select ID type" /></SelectTrigger></FormControl>
                    <SelectContent>{ID_TYPES.map(t => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}</SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )} />
              <FormField control={form.control} name="idNumber" render={({ field }) => (
                <FormItem><FormLabel>ID Number</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </FormRow>

            {needsExpiry && (
              <FormField control={form.control} name="idExpiryDate" render={({ field }) => (
                <FormItem>
                  <FormLabel>ID Expiry Date <span className="text-destructive">*</span></FormLabel>
                  <FormControl><Input type="date" min={new Date().toISOString().slice(0, 10)} {...field} /></FormControl>
                  <FormDescription className="text-xs">Must not be expired as of today.</FormDescription>
                  <FormMessage />
                </FormItem>
              )} />
            )}

            <FormItem>
              <FormLabel>Upload New ID Document</FormLabel>
              <div className="flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-border bg-secondary/30 p-4 gap-2 cursor-pointer hover:border-primary/50 transition-colors"
                onClick={() => fileInputRef.current?.click()}>
                {idFile ? (
                  <>
                    <p className="text-sm font-medium truncate max-w-full px-2">{idFile.name}</p>
                    <p className="text-xs text-muted-foreground">{(idFile.size / 1024).toFixed(0)} KB</p>
                    <Button type="button" variant="ghost" size="sm" className="text-xs h-7 text-destructive"
                      onClick={e => { e.stopPropagation(); setIdFile(null); if (fileInputRef.current) fileInputRef.current.value = ''; }}>
                      Remove
                    </Button>
                  </>
                ) : (
                  <p className="text-sm text-muted-foreground">Click to replace document · JPG or PNG · max 5 MB</p>
                )}
              </div>
              <input ref={fileInputRef} type="file" accept=".jpg,.jpeg,.png" className="hidden" onChange={handleCustomerFileChange} />
              {fileError && <p className="text-xs text-destructive mt-1">{fileError}</p>}
            </FormItem>

            {customerKycChanged && (
              <>
                <Separator />
                <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 space-y-4">
                  <p className="text-xs font-semibold text-amber-800">KYC details changed — reason required.</p>
                  <FormField control={form.control} name="kycUpdateReason" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Reason <span className="text-destructive">*</span></FormLabel>
                      <Select onValueChange={field.onChange} value={field.value}>
                        <FormControl><SelectTrigger><SelectValue placeholder="Select reason…" /></SelectTrigger></FormControl>
                        <SelectContent>{KYC_REASONS.map(r => <SelectItem key={r} value={r}>{r}</SelectItem>)}</SelectContent>
                      </Select>
                      <FormMessage />
                    </FormItem>
                  )} />
                  <FormField control={form.control} name="kycUpdateNotes" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Additional Notes{selectedReason === 'Other' ? <span className="text-destructive"> *</span> : ' (optional)'}</FormLabel>
                      <FormControl><Textarea rows={2} className="resize-none" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )} />
                </div>
              </>
            )}

            {/* ── Directors (corporate only) ── */}
            {isCorporate && (
              <>
                <Separator />
                <div className="flex items-center justify-between">
                  <p className="text-sm font-semibold text-foreground">Directors</p>
                  <Button type="button" variant="outline" size="sm" onClick={addDirector}>+ Add Director</Button>
                </div>

                {tooFewDirectors && (
                  <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
                    At least 2 active directors are required. Add or restore a director before saving.
                  </div>
                )}

                {dirFields.map((field, i) => {
                  const dir       = watchedDirs[i] ?? field;
                  const isDeleted = dir.deleted;
                  const isNew     = !dir.id;
                  const dirKycChanged = isDirectorKycChanged(dir, i);
                  const dirReason     = form.watch(`directors.${i}.kycUpdateReason`);
                  const dirIdType     = form.watch(`directors.${i}.idType`) ?? '';
                  const dirNeedsExpiry = EXPIRY_TYPES.includes(dirIdType);

                  return (
                    <div key={field.id}
                      className={`rounded-lg border p-4 space-y-4 transition-opacity ${isDeleted ? 'opacity-40' : ''}`}>

                      {/* Director header */}
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <p className="text-sm font-medium">Director {i + 1}</p>
                          {isNew     && <Badge variant="outline"   className="text-[10px]">New</Badge>}
                          {isDeleted && <Badge variant="rejected"  className="text-[10px]">Removed</Badge>}
                        </div>
                        <Button type="button" size="sm"
                          variant={isDeleted ? 'outline' : 'ghost'}
                          className={isDeleted ? '' : 'text-destructive hover:text-destructive'}
                          onClick={() => toggleDeleteDirector(i, isDeleted)}>
                          {isDeleted ? 'Restore' : 'Remove'}
                        </Button>
                      </div>

                      {/* Director fields — hidden when deleted */}
                      {!isDeleted && (
                        <>
                          <FormRow>
                            <FormField control={form.control} name={`directors.${i}.firstName`} render={({ field: f }) => (
                              <FormItem><FormLabel>First Name</FormLabel><FormControl><Input {...f} /></FormControl><FormMessage /></FormItem>
                            )} />
                            <FormField control={form.control} name={`directors.${i}.lastName`} render={({ field: f }) => (
                              <FormItem><FormLabel>Last Name</FormLabel><FormControl><Input {...f} /></FormControl><FormMessage /></FormItem>
                            )} />
                          </FormRow>

                          <FormField control={form.control} name={`directors.${i}.dateOfBirth`} render={({ field: f }) => (
                            <FormItem><FormLabel>Date of Birth</FormLabel><FormControl><Input type="date" {...f} /></FormControl><FormMessage /></FormItem>
                          )} />

                          <FormRow>
                            <FormField control={form.control} name={`directors.${i}.idType`} render={({ field: f }) => (
                              <FormItem>
                                <FormLabel>ID Type</FormLabel>
                                <Select onValueChange={v => { f.onChange(v); form.setValue(`directors.${i}.idExpiryDate`, ''); }} value={f.value}>
                                  <FormControl><SelectTrigger><SelectValue placeholder="Select…" /></SelectTrigger></FormControl>
                                  <SelectContent>{ID_TYPES.map(t => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}</SelectContent>
                                </Select>
                                <FormMessage />
                              </FormItem>
                            )} />
                            <FormField control={form.control} name={`directors.${i}.idNumber`} render={({ field: f }) => (
                              <FormItem><FormLabel>ID Number</FormLabel><FormControl><Input {...f} /></FormControl><FormMessage /></FormItem>
                            )} />
                          </FormRow>

                          {dirNeedsExpiry && (
                            <FormField control={form.control} name={`directors.${i}.idExpiryDate`} render={({ field: f }) => (
                              <FormItem>
                                <FormLabel>ID Expiry Date <span className="text-destructive">*</span></FormLabel>
                                <FormControl><Input type="date" min={new Date().toISOString().slice(0, 10)} {...f} /></FormControl>
                                <FormMessage />
                              </FormItem>
                            )} />
                          )}

                          {/* Director document upload */}
                          <FormItem>
                            <FormLabel>ID Document</FormLabel>
                            <div className="flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-border bg-secondary/30 p-3 gap-1.5 cursor-pointer hover:border-primary/50 transition-colors"
                              onClick={() => dirFileRefs.current[i]?.click()}>
                              {dirFiles[i] ? (
                                <>
                                  <p className="text-sm font-medium truncate max-w-full px-2">{dirFiles[i]!.name}</p>
                                  <Button type="button" variant="ghost" size="sm" className="text-xs h-7 text-destructive"
                                    onClick={e => { e.stopPropagation(); removeDirFile(i); }}>Remove</Button>
                                </>
                              ) : (
                                <p className="text-xs text-muted-foreground">Click to replace document · JPG or PNG · max 5 MB</p>
                              )}
                            </div>
                            <input ref={el => { dirFileRefs.current[i] = el; }} type="file" accept=".jpg,.jpeg,.png"
                              className="hidden" onChange={e => handleDirFileChange(e, i)} />
                            {dirFileErrors[i] && <p className="text-xs text-destructive mt-1">{dirFileErrors[i]}</p>}
                          </FormItem>

                          {/* Director KYC reason block — only when KYC fields changed on existing director */}
                          {dirKycChanged && (
                            <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 space-y-4">
                              <p className="text-xs font-semibold text-amber-800">
                                Director KYC details changed — reason required.
                              </p>
                              <FormField control={form.control} name={`directors.${i}.kycUpdateReason`} render={({ field: f }) => (
                                <FormItem>
                                  <FormLabel>Reason <span className="text-destructive">*</span></FormLabel>
                                  <Select onValueChange={f.onChange} value={f.value}>
                                    <FormControl><SelectTrigger><SelectValue placeholder="Select reason…" /></SelectTrigger></FormControl>
                                    <SelectContent>{KYC_REASONS.map(r => <SelectItem key={r} value={r}>{r}</SelectItem>)}</SelectContent>
                                  </Select>
                                  <FormMessage />
                                </FormItem>
                              )} />
                              <FormField control={form.control} name={`directors.${i}.kycUpdateNotes`} render={({ field: f }) => (
                                <FormItem>
                                  <FormLabel>
                                    Additional Notes{dirReason === 'Other' ? <span className="text-destructive"> *</span> : ' (optional)'}
                                  </FormLabel>
                                  <FormControl><Textarea rows={2} className="resize-none" {...f} /></FormControl>
                                  <FormMessage />
                                </FormItem>
                              )} />
                            </div>
                          )}
                        </>
                      )}
                    </div>
                  );
                })}
              </>
            )}

            {save.isError && (
              <p className="text-xs text-destructive rounded border border-destructive/30 bg-destructive/10 px-3 py-2">
                Failed to save changes. Please check the details and try again.
              </p>
            )}

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={save.isPending || tooFewDirectors}>
                {save.isPending ? 'Saving…' : 'Save Changes'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}

// ─── Helpers (module-level) ───────────────────────────────────────────────────

function buildDefaults(customer: CustomerSnapshot): FormValues {
  return {
    email:           customer.email,
    phone:           customer.phone,
    address:         customer.address,
    contactPerson:   customer.contactPerson ?? '',
    brokerId:        customer.brokerId ?? '__none__',
    idType:          customer.idType        ?? '',
    idNumber:        customer.idNumber      ?? '',
    idExpiryDate:    customer.idExpiryDate  ?? '',
    kycUpdateReason: '',
    kycUpdateNotes:  '',
    directors:       (customer.directors ?? []).map(buildDefaultDirector),
  };
}
