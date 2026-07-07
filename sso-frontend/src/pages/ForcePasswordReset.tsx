import { useState } from "react";
import type { FormEvent } from "react";
import { Loader2 } from "lucide-react";
import { ApiError, errorMessage } from "../api";
import { changePassword, logout, getSession } from "../auth";
import type { SessionView } from "../auth";
import AuthLayout from "../components/layout/AuthLayout";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";

/**
 * First-login forced password reset. The user signed in with an admin-issued TEMPORARY password; the server
 * refuses to finalize the session (next === "MUST_RESET_PASSWORD") until they set their own. Submitting the
 * new password clears the requirement server-side and returns the finalized session.
 */
export default function ForcePasswordReset({ session, onDone }:
  { session: SessionView; onDone: (s: SessionView) => void }) {
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (password.length < 8) {
      setError("Choose a password of at least 8 characters.");
      return;
    }
    if (password !== confirm) {
      setError("The passwords don't match.");
      return;
    }
    setBusy(true);
    try {
      onDone(await changePassword(password));
    } catch (e) {
      setError(e instanceof ApiError ? errorMessage(e) : "We couldn't update your password. Please try again.");
      setBusy(false);
    }
  }

  // Abandon the reset and return to the sign-in screen (clears the half-authenticated session).
  async function cancel() {
    try { await logout(); } catch { /* ignore */ }
    onDone(await getSession());
  }

  return (
    <AuthLayout
      title="Set a new password"
      description="Your account was created with a temporary password. Choose your own to finish signing in."
      org={session.org}
      onBack={cancel}
      backLabel="Cancel and sign out"
    >
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="new-password">New password</Label>
          <Input id="new-password" type="password" value={password} autoFocus required
                 autoComplete="new-password" minLength={8}
                 onChange={(e) => setPassword(e.target.value)} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="confirm-password">Confirm password</Label>
          <Input id="confirm-password" type="password" value={confirm} required
                 autoComplete="new-password" onChange={(e) => setConfirm(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          Set password and continue
        </Button>
      </form>
    </AuthLayout>
  );
}
