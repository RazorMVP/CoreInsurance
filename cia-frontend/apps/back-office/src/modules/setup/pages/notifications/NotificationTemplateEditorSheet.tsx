import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Badge,
  Button,
  ConfirmDeleteDialog,
  Input,
  Label,
  Separator,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  Skeleton,
  Textarea,
} from '@cia/ui';
import {
  type NotificationChannel,
  type NotificationTemplateDto,
  type NotificationTemplateRequest,
  type NotificationTemplateType,
} from '@cia/api-client';
import {
  useNotificationTemplateDefaults,
  useNotificationTemplateVariables,
  usePreviewNotificationTemplate,
  useResetNotificationTemplate,
  useSaveNotificationTemplate,
} from '../../hooks/useNotificationTemplates';

// Frontend-only realistic sample values used to render the live preview.
const SAMPLE_VALUES: Record<string, string> = {
  customerName: 'Acme Logistics Ltd',
  beneficiaryName: 'Adjustment Partners Ltd',
  amount: '₦450,000.00',
  paymentDate: '2026-05-27',
  receiptNumber: 'REC-2026-00042',
  debitNoteNumber: 'DN-2026-00128',
  paymentNumber: 'PAY-2026-00017',
  creditNoteNumber: 'CN-2026-00073',
  companyName: 'Tenant Insurance Plc',
};

const TYPE_LABELS: Record<NotificationTemplateType, string> = {
  RECEIPT: 'Receipt',
  PAYMENT_VOUCHER: 'Payment Voucher',
};

const CHANNEL_LABELS: Record<NotificationChannel, string> = {
  EMAIL: 'Email',
  SMS: 'SMS',
};

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  templateType: NotificationTemplateType;
  channel: NotificationChannel;
  existingOverride: NotificationTemplateDto | null;
}

export default function NotificationTemplateEditorSheet({
  open,
  onOpenChange,
  templateType,
  channel,
  existingOverride,
}: Props) {
  const isEmail = channel === 'EMAIL';

  const defaultsQuery = useNotificationTemplateDefaults();
  const variablesQuery = useNotificationTemplateVariables();
  const saveMut = useSaveNotificationTemplate();
  const resetMut = useResetNotificationTemplate();
  const previewMut = usePreviewNotificationTemplate();

  const bodyRef = useRef<HTMLTextAreaElement | null>(null);

  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [confirmReset, setConfirmReset] = useState(false);

  // Effective defaults for this (type, channel) — the JAR-bundled fallback.
  const defaultEntry = useMemo(
    () =>
      defaultsQuery.data?.defaults.find(
        (d) => d.templateType === templateType && d.channel === channel,
      ) ?? null,
    [defaultsQuery.data, templateType, channel],
  );
  const defaultSubject = defaultEntry?.subjectTemplate ?? '';
  const defaultBody = defaultEntry?.bodyTemplate ?? '';

  // Allowed variables for this (type, channel).
  const allowedVariables = useMemo(
    () =>
      variablesQuery.data?.variables.find(
        (v) => v.templateType === templateType && v.channel === channel,
      )?.allowedVariables ?? [],
    [variablesQuery.data, templateType, channel],
  );

  // Initialise editor to the EFFECTIVE current template: override if present
  // (and non-null per field), otherwise the JAR default.
  useEffect(() => {
    if (!open) return;
    const effSubject =
      existingOverride?.subjectTemplate != null
        ? existingOverride.subjectTemplate
        : defaultSubject;
    const effBody =
      existingOverride?.bodyTemplate != null
        ? existingOverride.bodyTemplate
        : defaultBody;
    setSubject(effSubject);
    setBody(effBody);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, templateType, channel, existingOverride, defaultSubject, defaultBody]);

  // Debounced live preview (200ms) whenever subject/body change while open.
  useEffect(() => {
    if (!open) return;
    const handle = setTimeout(() => {
      previewMut.mutate({
        templateType,
        channel,
        subjectTemplate: isEmail ? subject : null,
        bodyTemplate: body,
        sampleValues: SAMPLE_VALUES,
      });
    }, 200);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, subject, body, templateType, channel]);

  const renderedSubject = previewMut.data?.subject ?? '';
  const renderedBody = previewMut.data?.body ?? '';

  // Insert {{varName}} at the body textarea's cursor position.
  function insertVariable(varName: string) {
    const token = `{{${varName}}}`;
    const el = bodyRef.current;
    if (!el) {
      setBody((prev) => prev + token);
      return;
    }
    const start = el.selectionStart ?? body.length;
    const end = el.selectionEnd ?? body.length;
    const next = body.slice(0, start) + token + body.slice(end);
    setBody(next);
    // Restore focus + place cursor after the inserted token.
    requestAnimationFrame(() => {
      el.focus();
      const caret = start + token.length;
      el.setSelectionRange(caret, caret);
    });
  }

  // Partial-override: send unchanged-from-default fields as null.
  const subjectDiffers = isEmail && subject !== defaultSubject;
  const bodyDiffers = body !== defaultBody;
  const nothingChanged = !subjectDiffers && !bodyDiffers;
  const isOverridden = existingOverride !== null;

  function handleSave() {
    const req: NotificationTemplateRequest = {
      templateType,
      channel,
      subjectTemplate: isEmail ? (subjectDiffers ? subject : null) : null,
      bodyTemplate: bodyDiffers ? body : null,
    };
    saveMut.mutate(
      { id: existingOverride?.id, req },
      { onSuccess: () => onOpenChange(false) },
    );
  }

  function handleReset(_reason: string) {
    if (!existingOverride) return;
    resetMut.mutate(existingOverride.id, {
      onSuccess: () => {
        setConfirmReset(false);
        onOpenChange(false);
      },
    });
  }

  const refLoading = defaultsQuery.isLoading || variablesQuery.isLoading;
  const segments = Math.max(1, Math.ceil(renderedBody.length / 160));

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full sm:max-w-[60vw] overflow-y-auto"
      >
        <SheetHeader>
          <div className="flex items-center gap-3">
            <SheetTitle>
              {TYPE_LABELS[templateType]} — {CHANNEL_LABELS[channel]}
            </SheetTitle>
            {nothingChanged ? (
              <Badge variant="outline" className="text-muted-foreground">
                Showing default
              </Badge>
            ) : (
              <Badge variant="default">Overridden</Badge>
            )}
          </div>
          <SheetDescription>
            Edit the copy on the left; the live preview on the right renders with
            sample values. Unchanged fields stay on the system default.
          </SheetDescription>
        </SheetHeader>

        {refLoading ? (
          <div className="mt-6 space-y-3">
            <Skeleton className="h-8 w-1/3" />
            <Skeleton className="h-[300px] w-full" />
          </div>
        ) : (
          <div className="mt-6 grid grid-cols-2 gap-6">
            {/* ── Left: editor ─────────────────────────────────────────── */}
            <div className="space-y-4">
              {isEmail && (
                <div className="space-y-1.5">
                  <Label htmlFor="tpl-subject">Subject</Label>
                  <Input
                    id="tpl-subject"
                    className="font-mono text-sm"
                    value={subject}
                    onChange={(e) => setSubject(e.target.value)}
                  />
                </div>
              )}

              <div className="space-y-1.5">
                <Label htmlFor="tpl-body">Body</Label>
                <Textarea
                  id="tpl-body"
                  ref={bodyRef}
                  className="font-mono text-sm min-h-[300px]"
                  value={body}
                  onChange={(e) => setBody(e.target.value)}
                />
                {channel === 'SMS' && (
                  <p className="text-xs text-muted-foreground">
                    {renderedBody.length} chars · {segments} segment(s)
                  </p>
                )}
              </div>

              <div className="space-y-2">
                <Label>Available variables</Label>
                <div className="flex flex-wrap gap-2">
                  {allowedVariables.length === 0 ? (
                    <span className="text-xs text-muted-foreground">
                      No variables for this template.
                    </span>
                  ) : (
                    allowedVariables.map((v) => (
                      <button
                        key={v}
                        type="button"
                        onClick={() => insertVariable(v)}
                        className="rounded border px-2 py-1 text-xs font-mono transition-colors hover:bg-muted"
                      >
                        {v} <span className="text-muted-foreground">[Insert]</span>
                      </button>
                    ))
                  )}
                </div>
                <p className="text-xs text-muted-foreground">
                  Use {'{{{variable}}}'} for unescaped HTML.
                </p>
              </div>
            </div>

            {/* ── Right: preview ───────────────────────────────────────── */}
            <div className="space-y-3">
              <Label>Preview</Label>
              {isEmail ? (
                <div className="space-y-3">
                  <h3 className="text-sm font-semibold">
                    {renderedSubject || (
                      <span className="text-muted-foreground">(no subject)</span>
                    )}
                  </h3>
                  <Separator />
                  <iframe
                    title="Email preview"
                    className="w-full min-h-[400px] border-0 bg-white rounded"
                    srcDoc={renderedBody}
                    sandbox=""
                  />
                </div>
              ) : (
                <pre className="whitespace-pre-wrap text-sm rounded border bg-muted/30 p-3 min-h-[200px]">
                  {renderedBody}
                </pre>
              )}
            </div>
          </div>
        )}

        {/* ── Footer ─────────────────────────────────────────────────── */}
        <div className="mt-8 flex items-center justify-between gap-3 border-t pt-4">
          <Button
            variant="destructive"
            disabled={!isOverridden || resetMut.isPending}
            onClick={() => setConfirmReset(true)}
          >
            Reset to default
          </Button>
          <div className="flex items-center gap-2">
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button
              onClick={handleSave}
              disabled={saveMut.isPending || nothingChanged}
            >
              {saveMut.isPending ? 'Saving…' : 'Save & activate'}
            </Button>
          </div>
        </div>
      </SheetContent>

      <ConfirmDeleteDialog
        open={confirmReset}
        onOpenChange={setConfirmReset}
        entityLabel="Template override"
        entityName={`${TYPE_LABELS[templateType]} — ${CHANNEL_LABELS[channel]}`}
        onConfirm={handleReset}
        busy={resetMut.isPending}
      />
    </Sheet>
  );
}
