import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { ChevronLeft, Fingerprint, Loader2, Lock, Smartphone } from "lucide-react";
import { ApiError, registerStepUpHandler } from "@/api";
import type { StepUpReason } from "@/api";
import { reauthPrepare, reauthVerify } from "@/auth";
import type { SessionView } from "@/auth";
import { assertFactorCredential, webAuthnSupported } from "@/webauthn";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { OtpInput } from "@/components/auth/OtpInput";

type Method = "choose" | "totp";

/**
 * Step-up re-authentication modal. First lets the user PICK an available factor (passkey / authenticator),
 * then shows that factor's input. Registered with the API layer so any sensitive request the server
 * rejects with X-Step-Up-Required prompts for a fresh factor and is retried automatically.
 */
export function StepUpProvider({ session, children }: { session: SessionView; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<StepUpReason>("action");
  const [method, setMethod] = useState<Method>("choose");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const resolver = useRef<((ok: boolean) => void) | null>(null);
  const pending = useRef<Promise<boolean> | null>(null);

  const passkeyAvailable = session.fido2Enrolled && webAuthnSupported();
  const totpAvailable = session.totpEnrolled;

  const prompt = useCallback((why: StepUpReason) => {
    if (pending.current) {
      return pending.current; // a modal is already open — reuse it instead of clobbering the resolver
    }
    setReason(why); setMethod("choose"); setCode(""); setError(null); setBusy(false); setOpen(true);
    pending.current = new Promise<boolean>((resolve) => { resolver.current = resolve; });
    return pending.current;
  }, []);

  useEffect(() => {
    registerStepUpHandler(prompt);
    return () => registerStepUpHandler(null);
  }, [prompt]);

  function finish(ok: boolean) {
    resolver.current?.(ok);
    resolver.current = null;
    pending.current = null;
    setOpen(false);
    setBusy(false);
  }

  async function verifyTotp(event: React.FormEvent) {
    event.preventDefault();
    setError(null); setBusy(true);
    try {
      await reauthVerify("TOTP", { code });
      finish(true);
    } catch (e) {
      setError(e instanceof ApiError ? "Invalid code — try again." : "Re-authentication failed.");
      setBusy(false);
    }
  }

  async function verifyPasskey() {
    setError(null); setBusy(true);
    try {
      const prepared = await reauthPrepare("FIDO2");
      await reauthVerify("FIDO2", { credential: await assertFactorCredential(prepared) });
      finish(true);
    } catch (e) {
      setError(e instanceof ApiError ? "Passkey re-authentication failed." : "Passkey ceremony was cancelled.");
      setBusy(false);
    }
  }

  const noMethods = !passkeyAvailable && !totpAvailable;

  return (
    <>
      {children}
      <Dialog open={open} onOpenChange={(o) => { if (!o) finish(false); }}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <div className="flex items-start gap-3">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-accent text-primary">
                <Lock className="size-5" />
              </span>
              <div className="space-y-1">
                <DialogTitle>{reason === "session" ? "Session re-authentication" : "Confirm it's you"}</DialogTitle>
                <DialogDescription>
                  {reason === "session"
                    ? "It's been a while — please verify it's still you to keep your session active."
                    : "This action is sensitive — please re-authenticate to continue."}
                </DialogDescription>
              </div>
            </div>
          </DialogHeader>

          {error && <p className="text-sm text-destructive">{error}</p>}

          {/* Step 1: pick an available method */}
          {method === "choose" && !noMethods && (
            <div className="space-y-2">
              <p className="text-sm text-muted-foreground">Choose how to verify:</p>
              {passkeyAvailable && (
                <Button type="button" variant="outline" className="h-12 w-full justify-start gap-3" onClick={verifyPasskey} disabled={busy}>
                  {busy ? <Loader2 className="animate-spin" /> : <Fingerprint className="text-primary" />}
                  <span className="flex flex-col items-start">
                    <span className="font-medium">Use your passkey</span>
                    <span className="text-xs text-muted-foreground">Fingerprint, PIN, or security key</span>
                  </span>
                </Button>
              )}
              {totpAvailable && (
                <Button type="button" variant="outline" className="h-12 w-full justify-start gap-3"
                        onClick={() => { setError(null); setMethod("totp"); }} disabled={busy}>
                  <Smartphone className="text-primary" />
                  <span className="flex flex-col items-start">
                    <span className="font-medium">Authenticator app</span>
                    <span className="text-xs text-muted-foreground">Enter a 6-digit TOTP code</span>
                  </span>
                </Button>
              )}
            </div>
          )}

          {/* Step 2a: TOTP code entry */}
          {method === "totp" && (
            <form onSubmit={verifyTotp} className="space-y-3">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Smartphone className="size-4" /> Enter the code from your authenticator
              </div>
              <OtpInput value={code} onChange={(e) => setCode(e.target.value)} />
              <Button type="submit" className="w-full" disabled={busy}>
                {busy ? <Loader2 className="animate-spin" /> : <Lock />} Verify
              </Button>
            </form>
          )}

          {noMethods && (
            <p className="text-sm text-muted-foreground">
              No re-authentication factor is enrolled. Set up an authenticator or passkey in your profile first.
            </p>
          )}

          {method === "totp" && passkeyAvailable ? (
            <Button type="button" variant="ghost" className="w-full" onClick={() => { setError(null); setCode(""); setMethod("choose"); }} disabled={busy}>
              <ChevronLeft /> Use a different method
            </Button>
          ) : null}

          <Button type="button" variant="ghost" className="w-full" onClick={() => finish(false)} disabled={busy}>Cancel</Button>
        </DialogContent>
      </Dialog>
    </>
  );
}
