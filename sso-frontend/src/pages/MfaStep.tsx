import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Fingerprint, Loader2, Lock, Mail } from "lucide-react";
import { getSession, logout, prepareFactor } from "../auth";
import type { FactorChallenge, SessionView } from "../auth";
import { webAuthnSupported } from "../webauthn";
import { factorMeta } from "../factors";
import { useFactorVerification } from "../hooks/useFactorVerification";
import AuthLayout from "../components/layout/AuthLayout";
import { FactorChooser } from "../components/auth/FactorChooser";
import { OtpInput } from "../components/auth/OtpInput";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";

/** Within a choice step, prefer a factor the user can use right now (no enrollment needed). */
function preferredFactor(factors: string[], session: SessionView): string {
  const usable = factors.find((f) =>
    f === "PASSWORD" || f === "EMAIL"
    || (f === "TOTP" && session.totpEnrolled) || (f === "FIDO2" && session.fido2Enrolled));
  return usable ?? factors[0];
}

/** Completes the current authentication-policy step; the user picks one allowed factor. */
export default function MfaStep({ session, onDone }: { session: SessionView; onDone: (s: SessionView) => void }) {
  const { t } = useTranslation("auth");
  const factors = session.pendingFactors;
  const {
    factor, setFactor, code, setCode, password, setPassword, emailSent,
    error, setError, busy, setBusy, submitCode, submitPassword, sendEmail, fido2, fido2Register,
  } = useFactorVerification({ initialFactor: preferredFactor(factors, session), onSuccess: onDone });
  const [challenge, setChallenge] = useState<FactorChallenge | null>(null);

  const needEnroll = factor === "TOTP" && !session.totpEnrolled;
  // Enrollment during login is gated by policy: when disabled, an un-enrolled TOTP can't be set up here.
  const enrollBlocked = needEnroll && !session.mfaEnrollmentAllowed;
  const pendingKey = factors.join(",");

  // When the policy advances to a new step, re-select a usable factor and re-enable controls
  // (the component is reused across steps, so a stale factor/busy must be cleared).
  useEffect(() => {
    if (!factors.includes(factor)) setFactor(preferredFactor(factors, session));
    setBusy(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingKey]);

  // For an un-enrolled TOTP (when enrollment during login is allowed), fetch the QR/secret to scan.
  useEffect(() => {
    setChallenge(null);
    if (factor === "TOTP" && !session.totpEnrolled && session.mfaEnrollmentAllowed) {
      prepareFactor("TOTP").then(setChallenge).catch(() => setError("Could not start enrollment"));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [factor, session.totpEnrolled]);

  // Go back to the email screen to sign in as someone else (clears the identified session).
  async function useDifferentEmail() {
    try { await logout(); } catch { /* ignore */ }
    onDone(await getSession());
  }

  const Icon = factorMeta(factor).icon;

  return (
    <AuthLayout
      onBack={useDifferentEmail}
      backLabel="Back to sign in"
      org={session.org}
      step={`Signing in${session.username ? ` as ${session.username}` : ""}`}
      title="Verify your identity"
      description={needEnroll
        ? "Set up your authenticator app to finish securing your account."
        : `Complete the ${t(factorMeta(factor).label)} step required by your sign-in policy.`}
    >
      <FactorChooser factors={factors} value={factor} onSelect={setFactor} />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      {factor === "FIDO2" && (session.fido2Enrolled ? (
        <Button type="button" className="w-full" onClick={fido2} disabled={busy || !webAuthnSupported()}>
          {busy ? <Loader2 className="animate-spin" /> : <Fingerprint />} Use your passkey
        </Button>
      ) : session.mfaEnrollmentAllowed ? (
        <div className="space-y-2">
          <Button type="button" className="w-full" onClick={fido2Register} disabled={busy || !webAuthnSupported()}>
            {busy ? <Loader2 className="animate-spin" /> : <Fingerprint />} Register a passkey &amp; continue
          </Button>
          <p className="text-center text-xs text-muted-foreground">
            Your device will prompt you to create a passkey, then you'll be signed in.
          </p>
        </div>
      ) : (
        <Alert variant="info"><AlertDescription>
          No passkey is set up, and registration during login is disabled. Ask an administrator to enable it.
        </AlertDescription></Alert>
      ))}

      {factor === "PASSWORD" && (
        <form onSubmit={submitPassword} className="space-y-3">
          <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                 placeholder="Password" autoFocus required />
          <Button type="submit" className="w-full" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" /> : <Lock />} Verify password
          </Button>
        </form>
      )}

      {factor === "EMAIL" && !emailSent && (
        <Button type="button" className="w-full" onClick={sendEmail}><Mail /> Email me a code</Button>
      )}

      {enrollBlocked && (
        <Alert variant="info"><AlertDescription>
          Your authenticator app isn't set up yet, and setup during login is disabled. Ask an administrator
          to enable it for your account.
        </AlertDescription></Alert>
      )}

      {needEnroll && !enrollBlocked && challenge?.qrDataUri && (
        <div className="mb-4 flex flex-col items-center gap-3 rounded-lg border bg-muted/40 p-4">
          <p className="text-sm text-muted-foreground">Scan with your authenticator app</p>
          <img src={challenge.qrDataUri} alt="TOTP QR code" width={170} height={170} className="rounded-md bg-white p-2 shadow-sm" />
          <details className="w-full text-center text-xs text-muted-foreground">
            <summary className="cursor-pointer">Enter key manually</summary>
            <code className="mt-1 block break-all font-mono">{challenge.secret}</code>
          </details>
        </div>
      )}

      {((factor === "TOTP" && !enrollBlocked) || (factor === "EMAIL" && emailSent)) && (
        <form onSubmit={submitCode} className="space-y-3">
          <OtpInput value={code} onChange={(e) => setCode(e.target.value)} />
          <Button type="submit" className="w-full" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" /> : <Icon />} {needEnroll ? "Verify & enroll" : "Verify"}
          </Button>
        </form>
      )}
    </AuthLayout>
  );
}
