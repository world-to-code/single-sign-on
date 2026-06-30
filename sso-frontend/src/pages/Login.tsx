import { useState } from "react";
import type { FormEvent } from "react";
import { Loader2 } from "lucide-react";
import { ApiError } from "../api";
import { identify } from "../auth";
import type { SessionView } from "../auth";
import AuthLayout from "../components/layout/AuthLayout";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";

/**
 * Entry screen. Sign-in is <b>identifier-first</b>: the user submits only their email and the
 * backend resolves their policy to drive the factor steps (password, passkey, TOTP, … appear only
 * if their policy uses them). There is no self-service signup — accounts are provisioned by an
 * administrator (invite-only).
 */
export default function Login({ onDone }: { onDone: (s: SessionView) => void }) {
  const [email, setEmail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      onDone(await identify(email));
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.status === 404
          ? "No account found for that email. Accounts are created by an administrator — please contact them."
          : "Could not start sign-in. Please try again.");
      }
      setBusy(false);
    }
  }

  return (
    <AuthLayout
      title="Sign in"
      description="Enter your email — we'll continue with your organization's sign-in policy."
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
