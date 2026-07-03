import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";

/**
 * A multi-line code block with a copy button — for snippets an integrator pastes. Long lines scroll
 * horizontally by default, or wrap onto the next line when {@code wrap} is set (e.g. a long URL).
 */
export function CodeBlock({ label, code, hint, wrap }: { label?: string; code: string; hint?: string; wrap?: boolean }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable (insecure context) — no-op */
    }
  }

  return (
    <div className="grid gap-1.5">
      {label && <Label>{label}</Label>}
      <div className="relative">
        <pre className={`rounded-md border bg-muted px-3 py-2 pr-11 font-mono text-xs leading-relaxed ${wrap ? "whitespace-pre-wrap break-all" : "overflow-x-auto"}`}>
          <code>{code}</code>
        </pre>
        <Button type="button" variant="outline" size="icon" onClick={copy} title="Copy to clipboard"
                aria-label="Copy" className="absolute right-1.5 top-1.5 size-7">
          {copied ? <Check className="size-3.5 text-green-600" /> : <Copy className="size-3.5" />}
        </Button>
      </div>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}
