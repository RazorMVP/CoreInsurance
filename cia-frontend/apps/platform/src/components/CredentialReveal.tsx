import { useState } from 'react';
import { Button } from '@cia/ui';

interface CredentialRevealProps {
  title: string;
  subtitle: string;
  identityLabel: string;
  identityValue: string;
  /** The one-time secret. Held only in this component's render scope — never persisted. */
  secret: string;
  onDone: () => void;
}

export default function CredentialReveal({
  title, subtitle, identityLabel, identityValue, secret, onDone,
}: CredentialRevealProps) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(secret);
    } catch {
      /* clipboard denied — still unlock Done; the value is visible to copy manually */
    }
    setCopied(true);
  }

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm font-semibold text-primary">✓ {title}</p>
        <p className="text-xs text-muted-foreground">{subtitle}</p>
      </div>

      <div className="rounded-md border border-amber-700/50 bg-amber-900/20 p-3">
        <p className="text-sm font-semibold text-amber-300">⚠ Copy the temporary password now</p>
        <p className="mt-0.5 text-xs text-amber-200/80">
          Shown once. It is never stored or retrievable. The account resets it on first login.
        </p>
      </div>

      <div>
        <p className="text-xs text-muted-foreground">{identityLabel}</p>
        <p className="mt-1 rounded-md border bg-secondary/40 px-3 py-2 text-sm text-foreground">{identityValue}</p>
      </div>

      <div>
        <p className="text-xs text-muted-foreground">Temporary password</p>
        <div className="mt-1 flex gap-2">
          <code className="flex-1 truncate rounded-md border border-primary/60 bg-secondary/40 px-3 py-2 font-mono text-sm tracking-wide text-foreground">
            {secret}
          </code>
          <Button type="button" onClick={copy} variant={copied ? 'outline' : 'default'}>
            {copied ? '✓ Copied' : '⧉ Copy'}
          </Button>
        </div>
      </div>

      <div className="flex justify-end pt-2">
        <Button type="button" onClick={onDone} disabled={!copied}>Done</Button>
      </div>
    </div>
  );
}
