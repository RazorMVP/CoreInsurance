import { useEffect, useRef, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import {
  Button,
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
  Input,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import type { TemplateRow } from './template-types';
import { TEMPLATE_TYPES } from './template-types';

// ── Schema ───────────────────────────────────────────────────────────────────
const templateSchema = z.object({
  name: z.string().min(2, 'Required'),
  type: z.enum(['POLICY_DOCUMENT','CERTIFICATE','SCHEDULE','DEBIT_NOTE','ENDORSEMENT','OTHER']),
});
type TemplateFormValues = z.infer<typeof templateSchema>;

// ── Props ────────────────────────────────────────────────────────────────────
interface Props {
  open:          boolean;
  onOpenChange:  (v: boolean) => void;
  productId:     string;
  productName:   string;
  /** When replacing an existing template, pre-fills type and shows the warning banner */
  replaceTemplate?: Pick<TemplateRow, 'id' | 'type'> | null;
  onSave:        (values: TemplateFormValues & { file: File; replaceId?: string }) => void;
}

export default function TemplateUploadSheet({
  open, onOpenChange, productId: _productId, productName, replaceTemplate, onSave,
}: Props) {
  const [file,     setFile]     = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [fileError,setFileError]= useState('');
  const fileInputRef             = useRef<HTMLInputElement>(null);

  const form = useForm<TemplateFormValues>({
    resolver:      zodResolver(templateSchema) as any,
    defaultValues: { name: '', type: 'POLICY_DOCUMENT' },
  });

  useEffect(() => {
    if (!open) {
      setFile(null);
      setFileError('');
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
    form.reset({
      name: '',
      type: replaceTemplate?.type ?? 'POLICY_DOCUMENT',
    });
  }, [open, replaceTemplate, form]);

  function acceptFile(f: File) {
    const valid = f.name.endsWith('.docx') || f.name.endsWith('.pdf');
    if (!valid) { setFileError('Only .docx and .pdf files are accepted.'); return; }
    if (f.size > 10 * 1024 * 1024) { setFileError('File must be under 10 MB.'); return; }
    setFileError('');
    setFile(f);
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(false);
    const f = e.dataTransfer.files[0];
    if (f) acceptFile(f);
  }

  function handleFileInput(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    if (f) acceptFile(f);
  }

  function onSubmit(values: TemplateFormValues) {
    if (!file) { setFileError('Please select a file.'); return; }
    onSave({ ...values, file, replaceId: replaceTemplate?.id });
    onOpenChange(false);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-[440px] sm:max-w-[440px] overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{replaceTemplate ? 'Replace Template' : 'Upload Template'}</SheetTitle>
          <SheetDescription>
            {replaceTemplate
              ? 'Uploading a new file will archive the current version.'
              : 'Upload a .docx or .pdf master template for this product.'}
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">

            {/* Product — read-only */}
            <FormItem>
              <FormLabel>Product</FormLabel>
              <Input value={productName} readOnly className="bg-muted text-muted-foreground cursor-default" />
              <p className="text-xs text-muted-foreground mt-1">Pre-filled from the product selector.</p>
            </FormItem>

            {/* Name */}
            <FormField control={form.control} name="name" render={({ field }) => (
              <FormItem>
                <FormLabel>Template Name</FormLabel>
                <FormControl><Input placeholder="e.g. Motor Comprehensive Policy v3" {...field} /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            {/* Type */}
            <FormField control={form.control} name="type" render={({ field }) => (
              <FormItem>
                <FormLabel>Template Type</FormLabel>
                <Select
                  value={field.value}
                  onValueChange={field.onChange}
                  disabled={!!replaceTemplate}
                >
                  <FormControl>
                    <SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {TEMPLATE_TYPES.map(t => (
                      <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {replaceTemplate && (
                  <p className="text-xs text-muted-foreground mt-1">Type is locked when replacing.</p>
                )}
                <FormMessage />
              </FormItem>
            )} />

            {/* File drop zone */}
            <FormItem>
              <FormLabel>File</FormLabel>
              <div
                onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
                onDragLeave={() => setDragOver(false)}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
                className={`rounded-lg border-2 border-dashed p-6 text-center cursor-pointer transition-colors ${dragOver ? 'border-primary bg-teal-50' : 'border-border hover:border-primary/50'}`}
              >
                {file ? (
                  <div>
                    <p className="text-sm font-medium text-foreground">{file.name}</p>
                    <p className="text-xs text-muted-foreground mt-1">{(file.size / 1024).toFixed(0)} KB — click to change</p>
                  </div>
                ) : (
                  <div>
                    <p className="text-sm font-medium text-foreground">Drop .docx or .pdf here</p>
                    <p className="text-xs text-muted-foreground mt-1">
                      or <span className="text-primary">browse to upload</span> · Max 10 MB
                    </p>
                  </div>
                )}
              </div>
              <input
                ref={fileInputRef}
                type="file"
                accept=".docx,.pdf"
                className="hidden"
                onChange={handleFileInput}
              />
              {fileError && <p className="text-sm font-medium text-destructive mt-1">{fileError}</p>}
            </FormItem>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit">Upload Template</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
