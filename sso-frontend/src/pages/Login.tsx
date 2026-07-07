import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { KeyRound, Loader2 } from "lucide-react";
import { ApiError } from "../api";
import { getSession, identify, logout } from "../auth";
import type { SessionView } from "../auth";
import {
  conditionalMediationAvailable,
  conditionalPasswordlessLogin,
  passwordlessLogin,
  webAuthnSupported,
} from "../webauthn";
import { lastEmail, rememberEmail } from "../lib/loginMemory";
import AuthLayout from "../components/layout/AuthLayout";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";

/**
 * Identifier step of the tenant-first flow. The organization is already resolved (shown as context); the
 * user submits only their email and the backend resolves their policy to drive the factor steps (password,
 * passkey, TOTP, … appear only if their policy uses them). There is no self-service signup — accounts are
 * provisioned by an administrator (invite-only). A returning user's email is prefilled for this org.
 */
export default function Login({ session, onDone }: { session: SessionView; onDone: (s: SessionView) => void }) {
  const org = session.org;
  const passkeyOffered = session.passwordlessLoginAllowed && webAuthnSupported();
  const [email, setEmail] = useState(() => (org ? lastEmail(org) ?? "" : ""));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [passkeyBusy, setPasskeyBusy] = useState(false);

  // Conditional-UI (autofill): when the tenant allows passwordless login, let the browser surface the
  // user's passkeys inline in the email field's autofill. Best-effort — silently ignored if unsupported or
  // aborted (the user types instead / the component unmounts).
  useEffect(() => {
    if (!passkeyOffered) return;
    const controller = new AbortController();
    let active = true;
    (async () => {
      try {
        if (!(await conditionalMediationAvailable())) return;
        const next = await conditionalPasswordlessLogin(controller.signal);
        if (active && next) onDone(next);
      } catch { /* conditional UI is best-effort */ }
    })();
    return () => { active = false; controller.abort(); };
  }, [passkeyOffered, onDone]);

  async function signInWithPasskey() {
    setError(null);
    setPasskeyBusy(true);
    try {
      onDone(await passwordlessLogin());
    } catch {
      setError("Passkey sign-in did not complete. You can continue with your email instead.");
      setPasskeyBusy(false);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const next = await identify(email);
      if (org) rememberEmail(org, email.trim());
      onDone(next);
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.status === 404
          ? "No account found for that email. Accounts are created by an administrator — please contact them."
          : "Could not start sign-in. Please try again.");
      }
      setBusy(false);
    }
  }

  // Return to organization selection: the resolved org lives in the pre-auth session, so clear it and
  // re-probe. The remembered org is kept, so the picker still offers it.
  async function useDifferentOrg() {
    try { await logout(); } catch { /* ignore */ }
    onDone(await getSession());
  }

  return (
    <AuthLayout
      title="Sign in"
      description="Enter your email to continue with your organization's sign-in policy."
      org={org}
      onBack={useDifferentOrg}
      backLabel="Use a different organization"
    >
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="email">Email</Label>
          <Input id="email" type="email" value={email} autoFocus required
                 autoComplete="username webauthn"
                 placeholder="you@example.com" onChange={(e) => setEmail(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          Continue
        </Button>
      </form>

      {passkeyOffered && (
        <>
          <div className="my-4 flex items-center gap-3 text-xs text-muted-foreground">
            <span className="h-px flex-1 bg-border" />
            or
            <span className="h-px flex-1 bg-border" />
          </div>
          <Button type="button" variant="outline" className="w-full" disabled={passkeyBusy}
                  onClick={signInWithPasskey}>
            {passkeyBusy ? <Loader2 className="animate-spin" /> : <KeyRound />}
            Sign in with a passkey
          </Button>
        </>
      )}

      <p className="mt-3 text-xs text-muted-foreground">
        We'll ask for your password or other factors based on your sign-in policy.
        Need an account? Contact your administrator.
      </p>
    </AuthLayout>
  );
}
