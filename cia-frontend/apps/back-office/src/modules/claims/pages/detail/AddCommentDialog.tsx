import { useState } from 'react';
import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  claimNumber:  string;
  onSuccess:    () => void;
}

export default function AddCommentDialog({ open, onOpenChange, claimNumber, onSuccess }: Props) {
  const [text, setText] = useState('');

  function handleClose() {
    setText('');
    onOpenChange(false);
  }

  async function handleSubmit() {
    if (text.trim().length < 3) return;
    console.log('Add comment', text);
    // TODO: POST /api/v1/claims/{id}/comments
    setText('');
    onSuccess();
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) handleClose(); }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>Add Comment</DialogTitle>
          <DialogDescription>
            Add a note to <span className="font-medium text-foreground">{claimNumber}</span>.
            Comments are visible to all claim officers and approvers.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-1.5">
          <label className="text-sm font-medium text-foreground">Comment</label>
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Enter your note or update on this claim…"
            rows={4}
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm resize-none focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          />
          <p className="text-xs text-muted-foreground">{text.trim().length} characters</p>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>Cancel</Button>
          <Button disabled={text.trim().length < 3} onClick={handleSubmit}>
            Add Comment
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
