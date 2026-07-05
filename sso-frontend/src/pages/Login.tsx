import { useState } from "react";
import type { FormEvent } from "react";
import { Loader2 } from "lucide-react";
import { ApiError } from "../api";
import { getSession, identify, logout } from "../auth";
import type { SessionView } from "../auth";
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
  const [email, setEmail] = useState(() => (org ? lastEmail(org) ?? "" : ""));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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

      <p className="mt-3 text-xs text-muted-foreground">
        We'll ask for your password or other factors based on your sign-in policy.
        Need an account? Contact your administrator.
      </p>
    </AuthLayout>
  );
}
