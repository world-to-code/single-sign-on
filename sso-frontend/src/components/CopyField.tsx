import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";

/**
 * A labelled, read-only value shown in a monospace box with a copy-to-clipboard button — for endpoint
 * URLs and identifiers an integrator needs to paste into their OIDC client configuration.
 */
export function CopyField({ label, value, hint }: { label: string; value: string; hint?: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable (insecure context) — no-op */
    }
  }

  return (
    <div className="grid gap-1.5">
      <Label>{label}</Label>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 truncate rounded-md border bg-muted px-3 py-2 font-mono text-xs" title={value}>
          {value}
        </code>
        <Button type="button" variant="outline" size="icon" onClick={copy} title="Copy to clipboard" aria-label="Copy">
          {copied ? <Check className="size-4 text-allow" /> : <Copy className="size-4" />}
        </Button>
      </div>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}
