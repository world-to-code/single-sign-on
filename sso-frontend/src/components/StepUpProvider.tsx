import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { ChevronLeft, Fingerprint, KeyRound, Loader2, Lock, Smartphone } from "lucide-react";
import { ApiError, registerStepUpHandler } from "@/api";
import type { StepUpReason } from "@/api";
import { logout, reauthPrepare, reauthVerify } from "@/auth";
import type { SessionView } from "@/auth";
import { assertFactorCredential, webAuthnSupported } from "@/webauthn";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { OtpInput } from "@/components/auth/OtpInput";

type Method = "choose" | "totp" | "password";

/**
 * Step-up / re-authentication modal. Lets the user PICK a factor allowed by the policy (passkey /
 * authenticator / password) and verify it. Registered with the API layer, so any request the server
 * rejects with X-Step-Up-Required prompts for a fresh factor and is retried automatically.
 *
 * <p>Two flavours by {@code reason}:
 *  - "action" — a sensitive operation; the user MAY cancel (they can change their mind).
 *  - "session" — the periodic MANDATORY re-authentication; it has no cancel/close and cannot be dismissed,
 *    because the server also gates the session on it (a dismissed modal would just be re-challenged).
 */
export function StepUpProvider({ session, children }: { session: SessionView; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<StepUpReason>("action");
  const [allowed, setAllowed] = useState<string[] | null>(null); // policy-allowed factors; null = no restriction
  const [method, setMethod] = useState<Method>("choose");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const resolver = useRef<((ok: boolean) => void) | null>(null);
  const pending = useRef<Promise<boolean> | null>(null);

  const allow = (factor: string) => allowed === null || allowed.includes(factor);
  const passkeyAvailable = allow("FIDO2") && session.fido2Enrolled && webAuthnSupported();
  const totpAvailable = allow("TOTP") && session.totpEnrolled;
  const passwordAvailable = allow("PASSWORD"); // the user always "has" their password
  const noMethods = !passkeyAvailable && !totpAvailable && !passwordAvailable;
  const mandatory = reason === "session";

  const prompt = useCallback((why: StepUpReason, factors?: string[]) => {
    if (pending.current) {
      return pending.current; // a modal is already open — reuse it instead of clobbering the resolver
    }
    setReason(why); setAllowed(factors ?? null); setMethod("choose");
    setCode(""); setPassword(""); setError(null); setBusy(false); setOpen(true);
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
    setCode(""); setPassword(""); // don't retain the entered secret after the prompt closes
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

  async function verifyPassword(event: React.FormEvent) {
    event.preventDefault();
    setError(null); setBusy(true);
    try {
      await reauthVerify("PASSWORD", { password });
      finish(true);
    } catch (e) {
      setError(e instanceof ApiError ? "Incorrect password — try again." : "Re-authentication failed.");
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

  async function signOut() {
    try { await logout(); } catch { /* ignore */ }
    window.location.href = "/login";
  }

  return (
    <>
      {children}
      <Dialog open={open} onOpenChange={(o) => { if (!o && !mandatory) finish(false); }}>
        <DialogContent
          className="max-w-md"
          overlayClassName="bg-background/40 backdrop-blur-md"
          hideClose={mandatory}
          onEscapeKeyDown={mandatory ? (e) => e.preventDefault() : undefined}
          onInteractOutside={mandatory ? (e) => e.preventDefault() : undefined}
        >
          <DialogHeader>
            <div className="flex items-start gap-3">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-accent text-primary">
                <Lock className="size-5" />
              </span>
              <div className="space-y-1">
                <DialogTitle>{mandatory ? "Re-authentication required" : "Confirm it's you"}</DialogTitle>
                <DialogDescription>
                  {mandatory
                    ? "Your session needs to be re-verified to continue. This step can't be skipped."
                    : "This action is sensitive — please re-authenticate to continue."}
                </DialogDescription>
              </div>
            </div>
          </DialogHeader>

          {error && <p className="text-sm text-destructive">{error}</p>}

          {/* Step 1: pick an allowed + available method */}
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
              {passwordAvailable && (
                <Button type="button" variant="outline" className="h-12 w-full justify-start gap-3"
                        onClick={() => { setError(null); setMethod("password"); }} disabled={busy}>
                  <KeyRound className="text-primary" />
                  <span className="flex flex-col items-start">
                    <span className="font-medium">Password</span>
                    <span className="text-xs text-muted-foreground">Re-enter your account password</span>
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

          {/* Step 2b: password entry */}
          {method === "password" && (
            <form onSubmit={verifyPassword} className="space-y-3">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <KeyRound className="size-4" /> Enter your account password
              </div>
              <Input type="password" autoFocus autoComplete="current-password" value={password}
                     onChange={(e) => setPassword(e.target.value)} placeholder="Password" />
              <Button type="submit" className="w-full" disabled={busy || !password}>
                {busy ? <Loader2 className="animate-spin" /> : <Lock />} Verify
              </Button>
            </form>
          )}

          {noMethods && (
            <p className="text-sm text-muted-foreground">
              No allowed re-authentication factor is available. Set up an authenticator or passkey in your profile first.
            </p>
          )}

          {/* Back to the method chooser (only when more than one method exists) */}
          {method !== "choose" && [passkeyAvailable, totpAvailable, passwordAvailable].filter(Boolean).length > 1 && (
            <Button type="button" variant="ghost" className="w-full"
                    onClick={() => { setError(null); setCode(""); setPassword(""); setMethod("choose"); }} disabled={busy}>
              <ChevronLeft /> Use a different method
            </Button>
          )}

          {/* "action" step-ups may be cancelled; the mandatory session re-auth may not. When mandatory and
              no factor is available, offer sign-out so the user is never fully stuck. */}
          {!mandatory && (
            <Button type="button" variant="ghost" className="w-full" onClick={() => finish(false)} disabled={busy}>Cancel</Button>
          )}
          {mandatory && noMethods && (
            <Button type="button" variant="ghost" className="w-full" onClick={signOut} disabled={busy}>Sign out</Button>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}
