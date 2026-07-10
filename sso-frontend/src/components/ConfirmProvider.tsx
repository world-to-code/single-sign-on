import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useReturnFocus } from "@/hooks/useReturnFocus";

export interface ConfirmOptions {
  title: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
  variant?: "default" | "destructive";
  /** Enumerate what will be destroyed BEFORE asking (DESIGN.md §7) — shown above the confirm controls. */
  blastRadius?: ReactNode;
  /** Type-to-confirm: the confirm button stays disabled until this exact phrase is typed. */
  confirmPhrase?: string;
}

type ConfirmFn = (opts: ConfirmOptions) => Promise<boolean>;
const ConfirmContext = createContext<ConfirmFn | null>(null);

/** Provides an imperative, styled replacement for window.confirm: `await confirm({...}) -> boolean`. */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [opts, setOpts] = useState<ConfirmOptions | null>(null);
  const [phrase, setPhrase] = useState("");
  const resolver = useRef<((value: boolean) => void) | null>(null);

  const { capture, restore } = useReturnFocus();

  const confirm = useCallback<ConfirmFn>((o) => {
    capture();
    setPhrase("");
    setOpts(o);
    return new Promise<boolean>((resolve) => { resolver.current = resolve; });
  }, [capture]);

  const close = useCallback((result: boolean) => {
    resolver.current?.(result);
    resolver.current = null;
    setOpts(null);
    restore();
  }, [restore]);

  // Clear the typed phrase whenever a new prompt opens, so a prior confirmation never carries over.
  useEffect(() => { if (opts) setPhrase(""); }, [opts]);

  const destructive = opts?.variant === "destructive";
  const phraseOk = !opts?.confirmPhrase || phrase === opts.confirmPhrase;

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      <Dialog open={!!opts} onOpenChange={(o) => { if (!o) close(false); }}>
        {opts && (
          <DialogContent className="max-w-md">
            <DialogHeader>
              <div className="flex items-start gap-3">
                <span className={destructive
                  ? "flex size-9 shrink-0 items-center justify-center rounded-full bg-destructive/10 text-destructive"
                  : "flex size-9 shrink-0 items-center justify-center rounded-full bg-accent text-primary"}>
                  <AlertTriangle className="size-5" />
                </span>
                <div className="space-y-1">
                  <DialogTitle>{opts.title}</DialogTitle>
                  {opts.description && <DialogDescription>{opts.description}</DialogDescription>}
                </div>
              </div>
            </DialogHeader>

            {opts.blastRadius && (
              <div className={destructive
                ? "rounded-[12px] border border-deny/30 bg-deny/[0.05] p-3 text-sm text-ink"
                : "rounded-[12px] bg-sunken p-3 text-sm text-ink"}>
                {opts.blastRadius}
              </div>
            )}

            {opts.confirmPhrase && (
              <div className="grid gap-1.5">
                <Label htmlFor="confirm-phrase">
                  Type <span className="font-mono font-semibold text-ink">{opts.confirmPhrase}</span> to confirm
                </Label>
                <Input
                  id="confirm-phrase"
                  autoFocus
                  autoComplete="off"
                  value={phrase}
                  onChange={(e) => setPhrase(e.target.value)}
                  aria-label={`Type ${opts.confirmPhrase} to confirm`}
                />
              </div>
            )}

            <DialogFooter>
              <Button variant="outline" onClick={() => close(false)}>{opts.cancelText ?? "Cancel"}</Button>
              <Button
                data-confirm
                variant={destructive ? "destructive" : "default"}
                disabled={!phraseOk}
                onClick={() => close(true)}
              >
                {opts.confirmText ?? "Confirm"}
              </Button>
            </DialogFooter>
          </DialogContent>
        )}
      </Dialog>
    </ConfirmContext.Provider>
  );
}

export function useConfirm(): ConfirmFn {
  const ctx = useContext(ConfirmContext);
  if (!ctx) throw new Error("useConfirm must be used within ConfirmProvider");
  return ctx;
}
