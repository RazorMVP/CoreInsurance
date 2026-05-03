import { useState } from 'react';
import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  claimId:      string;
  claimNumber:  string;
  onSuccess:    () => void;
}

export default function AddCommentDialog({ open, onOpenChange, claimId, claimNumber, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const [text, setText] = useState('');

  function handleClose() {
    setText('');
    onOpenChange(false);
  }

  const addComment = useMutation({
    mutationFn: async (comment: string) => {
      const res = await apiClient.post<{ data: { id: string } }>(
        `/api/v1/claims/${claimId}/comments`,
        { text: comment },
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['claims', claimId] });
      setText('');
      onSuccess();
    },
  });

  function handleSubmit() {
    if (text.trim().length < 3) return;
    addComment.mutate(text.trim());
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
          <Button disabled={text.trim().length < 3 || addComment.isPending} onClick={handleSubmit}>
            {addComment.isPending ? 'Saving…' : 'Add Comment'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
