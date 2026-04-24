import { useRef, useState } from 'react';
import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  documentName: string;
  onSuccess:    () => void;
}

export default function UploadDocumentDialog({ open, onOpenChange, documentName, onSuccess }: Props) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
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
    if (fileInputRef.current) fileInputRef.current.value = '';
    onOpenChange(false);
  }

  async function handleUpload() {
    if (!file) return;
    console.log('Upload document', documentName, file.name);
    // TODO: POST /api/v1/claims/{id}/documents
    handleClose();
    onSuccess();
  }

  const acceptedTypes = '.pdf,.jpg,.jpeg,.png,.doc,.docx';

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) handleClose(); }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>Upload Document</DialogTitle>
          <DialogDescription>
            Upload <span className="font-medium text-foreground">{documentName}</span> for this claim.
            Accepted: PDF, JPG, PNG, Word documents.
          </DialogDescription>
        </DialogHeader>

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
          <Button disabled={!file} onClick={handleUpload}>
            Upload
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
