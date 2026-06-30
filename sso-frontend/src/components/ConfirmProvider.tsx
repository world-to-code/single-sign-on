import { createContext, useCallback, useContext, useRef, useState } from "react";
import type { ReactNode } from "react";
import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

export interface ConfirmOptions {
  title: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
  variant?: "default" | "destructive";
}

type ConfirmFn = (opts: ConfirmOptions) => Promise<boolean>;
const ConfirmContext = createContext<ConfirmFn | null>(null);

/** Provides an imperative, styled replacement for window.confirm: `await confirm({...}) -> boolean`. */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [opts, setOpts] = useState<ConfirmOptions | null>(null);
  const resolver = useRef<((value: boolean) => void) | null>(null);

  const confirm = useCallback<ConfirmFn>((o) => {
    setOpts(o);
    return new Promise<boolean>((resolve) => { resolver.current = resolve; });
  }, []);

  const close = useCallback((result: boolean) => {
    resolver.current?.(result);
    resolver.current = null;
    setOpts(null);
  }, []);

  const destructive = opts?.variant === "destructive";

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
            <DialogFooter>
              <Button variant="outline" onClick={() => close(false)}>{opts.cancelText ?? "Cancel"}</Button>
              <Button variant={destructive ? "destructive" : "default"} onClick={() => close(true)}>
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
