import { useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
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
  const { t } = useTranslation("auth");
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
      setError(t("setPasswordMissingToken"));
      return;
    }
    if (password !== confirm) {
      setError(t("passwordsMismatch"));
      return;
    }
    setBusy(true);
    try {
      await setInvitationPassword(token, password);
      setDone(true);
    } catch (e) {
      setError(e instanceof ApiError ? errorMessage(e) : t("setPasswordFailed"));
      setBusy(false);
    }
  }

  if (done) {
    return (
      <AuthLayout title={t("setPasswordDoneTitle")} description={t("setPasswordDoneDesc")}>
        <div className="space-y-4 text-center">
          <CheckCircle2 className="mx-auto size-10 text-primary" />
          <p className="text-sm text-muted-foreground">
            {t("setPasswordDoneBody")}
          </p>
          <Button className="w-full" onClick={() => { window.location.href = "/"; }}>
            {t("continueToSignIn")}
          </Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={t("setPasswordTitle")} description={t("setPasswordDescription")}>
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="password">{t("newPassword")}</Label>
          <Input id="password" type="password" value={password} autoFocus required minLength={8}
                 autoComplete="new-password" onChange={(e) => setPassword(e.target.value)} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="confirm">{t("confirmPassword")}</Label>
          <Input id="confirm" type="password" value={confirm} required minLength={8}
                 autoComplete="new-password" onChange={(e) => setConfirm(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          {t("setPasswordSubmit")}
        </Button>
      </form>
      <p className="mt-3 text-xs text-muted-foreground">
        {t("setPasswordHint")}
      </p>
    </AuthLayout>
  );
}
