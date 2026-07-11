import { useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
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
  const { t } = useTranslation("auth");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (password.length < 8) {
      setError(t("forceResetTooShort"));
      return;
    }
    if (password !== confirm) {
      setError(t("passwordsMismatch"));
      return;
    }
    setBusy(true);
    try {
      onDone(await changePassword(password));
    } catch (e) {
      setError(e instanceof ApiError ? errorMessage(e) : t("forceResetFailed"));
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
      title={t("forceResetTitle")}
      description={t("forceResetDescription")}
      org={session.org}
      onBack={cancel}
      backLabel={t("forceResetCancel")}
    >
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="new-password">{t("newPassword")}</Label>
          <Input id="new-password" type="password" value={password} autoFocus required
                 autoComplete="new-password" minLength={8}
                 onChange={(e) => setPassword(e.target.value)} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="confirm-password">{t("confirmPassword")}</Label>
          <Input id="confirm-password" type="password" value={confirm} required
                 autoComplete="new-password" onChange={(e) => setConfirm(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          {t("forceResetSubmit")}
        </Button>
      </form>
    </AuthLayout>
  );
}
