import { useRef, useState } from 'react';
import {
  Button, Label,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  toast,
} from '@cia/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  type ApiError, type ApiResponse,
  type ClaimDocumentType,
} from '@cia/api-client';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

function showServerError(err: unknown, title: string) {
  const ax = err as ApiHttpError;
  const errors: ApiError[] = ax?.response?.data?.errors ?? [];
  const description = errors.length > 0
    ? errors.map(e => e.message).filter(Boolean).join('. ')
    : ax?.message ?? 'An unexpected error occurred. Please try again.';
  toast({ variant: 'destructive', title, description });
}

const DOCUMENT_TYPE_OPTIONS: { value: ClaimDocumentType; label: string }[] = [
  { value: 'CLAIM_FORM',         label: 'Claim Form' },
  { value: 'POLICE_REPORT',      label: 'Police Report' },
  { value: 'SURVEY_REPORT',      label: 'Survey Report' },
  { value: 'MEDICAL_REPORT',     label: 'Medical Report' },
  { value: 'PHOTOS',             label: 'Photos' },
  { value: 'REPAIR_ESTIMATE',    label: 'Repair Estimate' },
  { value: 'DISCHARGE_VOUCHER',  label: 'Discharge Voucher' },
  { value: 'OTHER',              label: 'Other' },
];

interface Props {
  open:                boolean;
  onOpenChange:        (v: boolean) => void;
  claimId:             string;
  /** Optional pre-selected document type — used when launched from a specific
   *  required-doc row. Omit to let the user pick. */
  initialDocumentType?: ClaimDocumentType;
  /** Optional context label to render in the dialog header. */
  contextLabel?:       string;
  onSuccess:           () => void;
}

export default function UploadDocumentDialog({
  open, onOpenChange, claimId, initialDocumentType, contextLabel, onSuccess,
}: Props) {
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [documentType, setDocumentType] = useState<ClaimDocumentType | ''>(initialDocumentType ?? '');
  const [dragOver, setDragOver] = useState(false);

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    setFile(e.target.files?.[0] ?? null);
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(false);
    const dropped = e.dataTransfer.files?.[0];
    if (dropped) setFile(dropped);
  }

  function handleClose() {
    setFile(null);
    setDocumentType(initialDocumentType ?? '');
    if (fileInputRef.current) fileInputRef.current.value = '';
    onOpenChange(false);
  }

  const upload = useMutation({
    mutationFn: async (f: File) => {
      if (!documentType) throw new Error('Pick a document type');
      const fd = new FormData();
      fd.append('file', f);
      const res = await apiClient.post<{ data: { id: string } }>(
        `/api/v1/claims/${claimId}/documents?documentType=${documentType}`,
        fd,
        { headers: { 'Content-Type': 'multipart/form-data' } },
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['claims', claimId, 'documents'] });
      queryClient.invalidateQueries({ queryKey: ['claims', claimId, 'required-documents'] });
      queryClient.invalidateQueries({ queryKey: ['claims', claimId] });
      toast({ title: 'Document uploaded' });
      handleClose();
      onSuccess();
    },
    onError: (e) => showServerError(e, 'Could not upload document'),
  });

  function handleUpload() {
    if (!file || !documentType) return;
    upload.mutate(file);
  }

  const acceptedTypes = '.pdf,.jpg,.jpeg,.png,.doc,.docx';
  const canSubmit = !!file && !!documentType && !upload.isPending;

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) handleClose(); }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>Upload Document</DialogTitle>
          <DialogDescription>
            {contextLabel
              ? <>Upload <span className="font-medium text-foreground">{contextLabel}</span> for this claim. </>
              : 'Attach a document (claim form, police report, photos, etc.) to this claim. '}
            Accepted: PDF, JPG, PNG, Word documents.
          </DialogDescription>
        </DialogHeader>

        {/* Document type picker */}
        <div className="space-y-1.5">
          <Label htmlFor="upload-doc-type">Document Type</Label>
          <Select value={documentType} onValueChange={(v) => setDocumentType(v as ClaimDocumentType)}>
            <SelectTrigger id="upload-doc-type">
              <SelectValue placeholder="Select document type" />
            </SelectTrigger>
            <SelectContent>
              {DOCUMENT_TYPE_OPTIONS.map(opt => (
                <SelectItem key={opt.value} value={opt.value}>{opt.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {/* File drop zone */}
        <div
          className={`rounded-lg border-2 border-dashed p-8 text-center transition-colors cursor-pointer ${dragOver ? 'border-primary bg-teal-50' : 'border-border bg-card hover:bg-muted/40'}`}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
          onClick={() => fileInputRef.current?.click()}
        >
          {file ? (
            <div className="space-y-1">
              <p className="text-sm font-medium text-foreground truncate">{file.name}</p>
              <p className="text-xs text-muted-foreground">{(file.size / 1024).toFixed(1)} KB</p>
              <button
                type="button"
                className="text-xs text-destructive hover:underline mt-1"
                onClick={(e) => { e.stopPropagation(); setFile(null); if (fileInputRef.current) fileInputRef.current.value = ''; }}
              >
                Remove
              </button>
            </div>
          ) : (
            <div className="space-y-1">
              <p className="text-sm text-muted-foreground">
                Drop file here or <span className="text-primary font-medium">browse</span>
              </p>
              <p className="text-xs text-muted-foreground">Max 10 MB</p>
            </div>
          )}
        </div>

        <input
          ref={fileInputRef}
          type="file"
          accept={acceptedTypes}
          className="sr-only"
          onChange={handleFileChange}
        />

        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>Cancel</Button>
          <Button disabled={!canSubmit} onClick={handleUpload}>
            {upload.isPending ? 'Uploading…' : 'Upload'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
