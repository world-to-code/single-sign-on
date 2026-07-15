import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ChevronLeft, Fingerprint, KeyRound, Loader2, Lock, ShieldAlert, Smartphone } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { ApiError, registerStepUpHandler } from "@/api";
import type { StepUpReason } from "@/api";
import { logout, reauthPrepare, reauthVerify } from "@/auth";
import { getSessionConfig } from "@/portal";
import type { SessionView } from "@/auth";
import { assertFactorCredential, webAuthnSupported } from "@/webauthn";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { OtpInput } from "@/components/auth/OtpInput";
import { useReturnFocus } from "@/hooks/useReturnFocus";

type Method = "choose" | "totp" | "password";

/** Failures are held as an i18n KEY, not a resolved string, so a locale switch re-renders them. */
type ErrorKey =
  | "reauthInvalidCode" | "reauthIncorrectPassword" | "reauthFailed"
  | "reauthPasskeyFailed" | "reauthPasskeyCancelled" | "reauthFactorNotAllowed";

/**
 * The i18n key for a failed re-auth verify: the server rejecting the factor as not-allowed for this step-up
 * ({@code auth.reauth.factorNotAllowed}) is a DIFFERENT message from a wrong secret — showing "incorrect
 * password" when the method simply isn't permitted (e.g. password on an MFA-only elevation) misleads the user.
 */
function reauthErrorKey(e: unknown, wrongSecret: ErrorKey, nonApi: ErrorKey): ErrorKey {
  if (e instanceof ApiError) {
    return e.code === "auth.reauth.factorNotAllowed" ? "reauthFactorNotAllowed" : wrongSecret;
  }
  return nonApi;
}

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
  const { t } = useTranslation("auth");
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<StepUpReason>("action");
  const [allowed, setAllowed] = useState<string[] | null>(null); // policy-allowed factors; null = no restriction
  const [method, setMethod] = useState<Method>("choose");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [errorKey, setErrorKey] = useState<ErrorKey | null>(null);
  const [busy, setBusy] = useState(false);
  // True while a proactive step-up is still fetching the policy's allowed factors — the method chooser is
  // withheld until then, so a method the server would reject (e.g. password) is NEVER momentarily offered.
  const [resolvingFactors, setResolvingFactors] = useState(false);
  const resolver = useRef<((ok: boolean) => void) | null>(null);
  const pending = useRef<Promise<boolean> | null>(null);
  // The policy's re-auth factors — the set the server's ReauthService allows for a step-up with no explicit
  // server challenge. Prefetched so a PROACTIVE step-up (entering the admin console) is constrained to them.
  const policyFactors = useRef<string[] | null>(null);

  useEffect(() => {
    getSessionConfig().then((cfg) => { policyFactors.current = cfg.reauthFactors; }).catch(() => undefined);
  }, []);

  // Admin elevation must mint an acr=mfa token: a session holding fewer than two distinct factors can only
  // get there by presenting a factor it does NOT already hold — re-verifying an already-held one (e.g. the
  // password of a password-only session) would mint another single-factor token and loop forever.
  const needsNewFactor = reason === "elevation" && new Set(session.factors).size < 2;
  const heldFactor = (factor: string) => session.factors.includes(`FACTOR_${factor}`);
  const allow = (factor: string) =>
    (allowed === null || allowed.includes(factor)) && !(needsNewFactor && heldFactor(factor));
  const passkeyAvailable = allow("FIDO2") && session.fido2Enrolled && webAuthnSupported();
  const totpAvailable = allow("TOTP") && session.totpEnrolled;
  const passwordAvailable = allow("PASSWORD"); // the user always "has" their password
  const noMethods = !passkeyAvailable && !totpAvailable && !passwordAvailable;
  const mandatory = reason === "session";

  // A row per policy-allowed factor. An allowed-but-unenrolled factor stays VISIBLE but disabled
  // ("Not enrolled") so the reader sees WHY it isn't offered, rather than it silently vanishing (§7).
  interface FactorRow {
    key: string;
    label: string;
    explain: string;
    keycap: string;
    Icon: LucideIcon;
    enrolled: boolean;
    onSelect: () => void;
  }
  const factorRows: FactorRow[] = [
    { key: "FIDO2", label: t("factorPasskey"), explain: t("reauthPasskeyExplain"), keycap: "1",
      Icon: Fingerprint, enrolled: session.fido2Enrolled && webAuthnSupported(), onSelect: () => verifyPasskey() },
    { key: "TOTP", label: t("factorTotp"), explain: t("reauthTotpExplain"), keycap: "2",
      Icon: Smartphone, enrolled: session.totpEnrolled, onSelect: () => { setErrorKey(null); setMethod("totp"); } },
    { key: "PASSWORD", label: t("factorPassword"), explain: t("reauthPasswordExplain"), keycap: "3",
      Icon: KeyRound, enrolled: true, onSelect: () => { setErrorKey(null); setMethod("password"); } },
  ].filter((r) => allow(r.key)); // allow() folds policy allow + the held-factor exclusion (not enrollment)
  const rowsRef = useRef<FactorRow[]>([]);
  rowsRef.current = factorRows;

  // Keycap shortcuts: 1/2/3 pick an enrolled factor. Only on the chooser step, where there is no text
  // input to swallow the digit, so it can't interfere with the TOTP/password entry forms.
  useEffect(() => {
    if (!open || method !== "choose" || resolvingFactors) return;
    const onKey = (e: KeyboardEvent) => {
      if (busy) return;
      const row = rowsRef.current.find((r) => r.enrolled && r.keycap === e.key);
      if (row) { e.preventDefault(); row.onSelect(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, method, resolvingFactors, busy]);

  const badgeText = reason === "elevation" ? t("reauthBadgeElevation") : t("reauthBadgeConfirm");
  const title = reason === "elevation"
    ? t("reauthTitleElevation")
    : mandatory ? t("reauthTitleSession") : t("reauthTitleAction");
  const description = reason === "elevation"
    ? t("reauthDescElevation")
    : mandatory ? t("reauthDescSession") : t("reauthDescAction");
  const requested = reason === "elevation"
    ? t("reauthRequestedElevation")
    : mandatory ? t("reauthRequestedSession") : t("reauthRequestedAction");
  const unlocks = reason === "elevation"
    ? t("reauthUnlocksElevation")
    : mandatory ? t("reauthUnlocksSession") : t("reauthUnlocksAction");

  const { capture, restore } = useReturnFocus();

  const prompt = useCallback((why: StepUpReason, factors?: string[]) => {
    if (pending.current) {
      return pending.current; // a modal is already open — reuse it instead of clobbering the resolver
    }
    capture();
    setReason(why); setMethod("choose");
    setCode(""); setPassword(""); setErrorKey(null); setBusy(false); setOpen(true);
    pending.current = new Promise<boolean>((resolve) => { resolver.current = resolve; });
    if (factors) {
      setAllowed(factors); setResolvingFactors(false); // reactive: the server named the exact allowed factors
    } else if (policyFactors.current) {
      setAllowed(policyFactors.current); setResolvingFactors(false); // proactive, factors already prefetched
    } else {
      // Proactive step-up (e.g. entering the admin console) before the prefetch resolved: WITHHOLD the method
      // chooser (spinner) until we know the policy's re-auth factors — the SAME set ReauthService accepts —
      // so a method it would reject (e.g. password on a TOTP/FIDO2-only policy) is never even offered.
      setAllowed(null); setResolvingFactors(true);
      getSessionConfig()
        .then((cfg) => { policyFactors.current = cfg.reauthFactors; setAllowed(cfg.reauthFactors); })
        // Config unreachable: offer NOTHING (empty), never leave `allowed` null — a null set makes the chooser
        // offer every factor, so it would present one the server then rejects (e.g. password on an MFA-only
        // elevation), which is exactly the "not allowed" dead-end. The modal can be closed and retried.
        .catch(() => setAllowed([]))
        .finally(() => setResolvingFactors(false));
    }
    return pending.current;
  }, [capture]);

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
    restore();
  }

  async function verifyTotp(event: React.FormEvent) {
    event.preventDefault();
    setErrorKey(null); setBusy(true);
    try {
      await reauthVerify("TOTP", { code });
      finish(true);
    } catch (e) {
      setErrorKey(reauthErrorKey(e, "reauthInvalidCode", "reauthFailed"));
      setBusy(false);
    }
  }

  async function verifyPassword(event: React.FormEvent) {
    event.preventDefault();
    setErrorKey(null); setBusy(true);
    try {
      await reauthVerify("PASSWORD", { password });
      finish(true);
    } catch (e) {
      setErrorKey(reauthErrorKey(e, "reauthIncorrectPassword", "reauthFailed"));
      setBusy(false);
    }
  }

  async function verifyPasskey() {
    setErrorKey(null); setBusy(true);
    try {
      const prepared = await reauthPrepare("FIDO2");
      await reauthVerify("FIDO2", { credential: await assertFactorCredential(prepared) });
      finish(true);
    } catch (e) {
      setErrorKey(reauthErrorKey(e, "reauthPasskeyFailed", "reauthPasskeyCancelled"));
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
            <span className="mb-1 inline-flex w-fit items-center gap-1.5 rounded-full border border-warn/30 bg-warn/10 px-2.5 py-0.5 text-xs font-semibold text-warn">
              <ShieldAlert className="size-3.5" /> {badgeText}
            </span>
            <div className="flex items-start gap-3">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-warn/10 text-warn">
                <Lock className="size-5" />
              </span>
              <div className="space-y-1">
                <DialogTitle>{title}</DialogTitle>
                <DialogDescription>{description}</DialogDescription>
              </div>
            </div>
          </DialogHeader>

          {/* Why this appeared and what complying buys — a modal without a stated reason trains click-through (§7). */}
          <dl className="space-y-1.5 rounded-[12px] bg-sunken p-3 text-sm">
            <div className="flex items-start justify-between gap-4">
              <dt className="text-muted-foreground">{t("reauthRequested")}</dt>
              <dd className="text-right font-medium text-ink">{requested}</dd>
            </div>
            <div className="flex items-start justify-between gap-4">
              <dt className="text-muted-foreground">{t("reauthAfterConfirm")}</dt>
              <dd className="text-right text-ink">{unlocks}</dd>
            </div>
          </dl>

          {errorKey && <p className="text-sm text-destructive">{t(errorKey)}</p>}

          {/* Still resolving which factors the policy allows — withhold the chooser so no rejected method shows */}
          {resolvingFactors && (
            <div className="flex items-center justify-center py-6">
              <Loader2 className="size-5 animate-spin text-muted-foreground" />
            </div>
          )}

          {/* Step 1: pick a factor. Allowed-but-unenrolled factors show as disabled rows, not omissions. */}
          {!resolvingFactors && method === "choose" && factorRows.length > 0 && (
            <div className="space-y-2">
              <p className="text-sm text-muted-foreground">{t("reauthChooseMethod")}</p>
              {factorRows.map((row) => {
                const Icon = row.Icon;
                if (!row.enrolled) {
                  return (
                    <div key={row.key}
                         className="flex w-full items-center gap-3 rounded-[12px] border border-line bg-sunken px-4 py-3 opacity-70">
                      <Icon className="size-5 shrink-0 text-faint" />
                      <span className="flex flex-1 flex-col items-start">
                        <span className="text-sm font-medium text-muted-foreground">{row.label}</span>
                        <span className="text-xs text-faint">{row.explain}</span>
                      </span>
                      <span className="shrink-0 text-xs font-medium text-faint">{t("reauthNotEnrolled")}</span>
                    </div>
                  );
                }
                return (
                  <Button key={row.key} type="button" variant="outline"
                          className="h-auto w-full justify-start gap-3 px-4 py-3" onClick={row.onSelect} disabled={busy}>
                    {busy && row.key === "FIDO2" ? <Loader2 className="animate-spin" /> : <Icon className="text-primary" />}
                    <span className="flex flex-1 flex-col items-start">
                      <span className="font-medium">{row.label}</span>
                      <span className="text-xs text-muted-foreground">{row.explain}</span>
                    </span>
                    <kbd className="shrink-0 rounded border border-line bg-surface px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground">{row.keycap}</kbd>
                  </Button>
                );
              })}
            </div>
          )}

          {/* Step 2a: TOTP code entry */}
          {method === "totp" && (
            <form onSubmit={verifyTotp} className="space-y-3">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Smartphone className="size-4" /> {t("reauthEnterTotp")}
              </div>
              <OtpInput value={code} onChange={(e) => setCode(e.target.value)} />
              <Button type="submit" className="w-full" disabled={busy}>
                {busy ? <Loader2 className="animate-spin" /> : <Lock />} {t("verify")}
              </Button>
            </form>
          )}

          {/* Step 2b: password entry */}
          {method === "password" && (
            <form onSubmit={verifyPassword} className="space-y-3">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <KeyRound className="size-4" /> {t("reauthEnterPassword")}
              </div>
              <Input type="password" autoFocus autoComplete="current-password" value={password}
                     onChange={(e) => setPassword(e.target.value)} placeholder={t("passwordPlaceholder")} />
              <Button type="submit" className="w-full" disabled={busy || !password}>
                {busy ? <Loader2 className="animate-spin" /> : <Lock />} {t("verify")}
              </Button>
            </form>
          )}

          {noMethods && (
            <p className="text-sm text-muted-foreground">
              {needsNewFactor ? t("reauthNoMethodsElevation") : t("reauthNoMethods")}
            </p>
          )}

          {/* Back to the method chooser (only when more than one method exists) */}
          {method !== "choose" && [passkeyAvailable, totpAvailable, passwordAvailable].filter(Boolean).length > 1 && (
            <Button type="button" variant="ghost" className="w-full"
                    onClick={() => { setErrorKey(null); setCode(""); setPassword(""); setMethod("choose"); }} disabled={busy}>
              <ChevronLeft /> {t("reauthDifferentMethod")}
            </Button>
          )}

          {/* "action" step-ups may be cancelled; the mandatory session re-auth may not. When mandatory and
              no factor is available, offer sign-out so the user is never fully stuck. */}
          {!mandatory && (
            <Button type="button" variant="ghost" className="w-full" onClick={() => finish(false)} disabled={busy}>
              {t("cancel")}
            </Button>
          )}
          {mandatory && noMethods && (
            <Button type="button" variant="ghost" className="w-full" onClick={signOut} disabled={busy}>
              {t("signOut")}
            </Button>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}
