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

const templateSchema = z.object({
  description: z.string().optional(),
  type:        z.enum(['POLICY', 'ENDORSEMENT', 'CLAIM_DV', 'NAICOM_CERTIFICATE']),
});
type TemplateFormValues = z.infer<typeof templateSchema>;

interface Props {
  open:             boolean;
  onOpenChange:     (v: boolean) => void;
  productId:        string;
  productName:      string;
  replaceTemplate?: Pick<TemplateRow, 'id' | 'type'> | null;
  onSave:           (values: TemplateFormValues & { file: File }) => Promise<void>;
}

export default function TemplateUploadSheet({
  open, onOpenChange, productName, replaceTemplate, onSave,
}: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [fileError, setFileError] = useState('');
  const [saving, setSaving] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const form = useForm<TemplateFormValues>({
    resolver:      zodResolver(templateSchema),
    defaultValues: { description: '', type: 'POLICY' },
  });

  useEffect(() => {
    if (!open) {
      setFile(null);
      setFileError('');
      setSaving(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
    form.reset({
      description: '',
      type:        replaceTemplate?.type ?? 'POLICY',
    });
  }, [open, replaceTemplate, form]);

  function acceptFile(f: File) {
    const lowerName = f.name.toLowerCase();
    const valid = lowerName.endsWith('.html') || lowerName.endsWith('.htm') || f.type === 'text/html';
    if (!valid) { setFileError('Only HTML template files are accepted.'); return; }
    if (f.size > 2 * 1024 * 1024) { setFileError('File must be under 2 MB.'); return; }
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

  async function onSubmit(values: TemplateFormValues) {
    if (!file) { setFileError('Please select a file.'); return; }
    setSaving(true);
    try {
      await onSave({ ...values, file });
      onOpenChange(false);
    } finally {
      setSaving(false);
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-[440px] sm:max-w-[440px] overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{replaceTemplate ? 'Replace Template' : 'Upload Template'}</SheetTitle>
          <SheetDescription>
            {replaceTemplate
              ? 'Uploading a new file will deactivate the current active template for this product and type.'
              : 'Upload an HTML master template for this product.'}
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-6 space-y-5">
            <FormItem>
              <FormLabel>Product</FormLabel>
              <Input value={productName} readOnly className="bg-muted text-muted-foreground cursor-default" />
            </FormItem>

            <FormField control={form.control} name="description" render={({ field }) => (
              <FormItem>
                <FormLabel>Description</FormLabel>
                <FormControl><Input placeholder="e.g. Motor policy template" {...field} /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="type" render={({ field }) => (
              <FormItem>
                <FormLabel>Template Type</FormLabel>
                <Select value={field.value} onValueChange={field.onChange} disabled={!!replaceTemplate}>
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

            <FormItem>
              <FormLabel>HTML File</FormLabel>
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
                    <p className="text-xs text-muted-foreground mt-1">{(file.size / 1024).toFixed(0)} KB - click to change</p>
                  </div>
                ) : (
                  <div>
                    <p className="text-sm font-medium text-foreground">Drop .html here</p>
                    <p className="text-xs text-muted-foreground mt-1">
                      or <span className="text-primary">browse to upload</span> · Max 2 MB
                    </p>
                  </div>
                )}
              </div>
              <input
                ref={fileInputRef}
                type="file"
                accept=".html,.htm,text/html"
                className="hidden"
                onChange={handleFileInput}
              />
              {fileError && <p className="text-sm font-medium text-destructive mt-1">{fileError}</p>}
            </FormItem>

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>Cancel</Button>
              <Button type="submit" disabled={saving}>{saving ? 'Uploading...' : 'Upload Template'}</Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
