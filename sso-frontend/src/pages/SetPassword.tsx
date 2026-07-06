import { useState } from "react";
import type { FormEvent } from "react";
import { CheckCircle2, Loader2 } from "lucide-react";
import { ApiError, errorMessage } from "../api";
import { setInvitationPassword } from "../onboarding";
import AuthLayout from "../components/layout/AuthLayout";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";

/**
 * Public landing for an onboarding invitation link ({@code /set-password?token=...}). The invited admin
 * has no credentials yet; redeeming the one-time token sets their password and activates the account, after
 * which they sign in normally. Mirrors the tenant-first auth screens (AuthLayout / Brand).
 */
export default function SetPassword() {
  const token = new URLSearchParams(window.location.search).get("token") ?? "";
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (!token) {
      setError("This link is missing its token — please use the link from your invitation email.");
      return;
    }
    if (password !== confirm) {
      setError("The passwords don't match.");
      return;
    }
    setBusy(true);
    try {
      await setInvitationPassword(token, password);
      setDone(true);
    } catch (e) {
      setError(e instanceof ApiError
        ? errorMessage(e)
        : "We couldn't set your password. The invitation link may have expired.");
      setBusy(false);
    }
  }

  if (done) {
    return (
      <AuthLayout title="You're all set" description="Your admin account is active.">
        <div className="space-y-4 text-center">
          <CheckCircle2 className="mx-auto size-10 text-primary" />
          <p className="text-sm text-muted-foreground">
            Your password has been set. You can now sign in to your workspace.
          </p>
          <Button className="w-full" onClick={() => { window.location.href = "/"; }}>
            Continue to sign in
          </Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title="Set your password" description="Activate your admin account to finish setting up your workspace.">
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="password">New password</Label>
          <Input id="password" type="password" value={password} autoFocus required minLength={8}
                 autoComplete="new-password" onChange={(e) => setPassword(e.target.value)} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="confirm">Confirm password</Label>
          <Input id="confirm" type="password" value={confirm} required minLength={8}
                 autoComplete="new-password" onChange={(e) => setConfirm(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          Set password
        </Button>
      </form>
      <p className="mt-3 text-xs text-muted-foreground">
        Use at least 8 characters. This is a one-time link and expires soon.
      </p>
    </AuthLayout>
  );
}
