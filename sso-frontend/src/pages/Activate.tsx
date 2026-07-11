import { useState } from "react";
import type { FormEvent } from "react";
import { Trans, useTranslation } from "react-i18next";
import { CheckCircle2, Loader2 } from "lucide-react";
import { ApiError, errorMessage } from "@/api";
import { activateWorkspace } from "@/onboarding";
import AuthLayout from "@/components/layout/AuthLayout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/**
 * Public landing for a self-service signup verification link ({@code /activate?token=...}). Redeeming the
 * one-time token proves the applicant controls the email and ONLY THEN creates the workspace + admin with
 * the chosen password — nothing was provisioned at signup time. Mirrors the tenant-first auth screens.
 */
export default function Activate() {
  const { t } = useTranslation("auth");
  const token = new URLSearchParams(window.location.search).get("token") ?? "";
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<{ slug: string; workspaceHost: string | null } | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (!token) {
      setError(t("activateMissingToken"));
      return;
    }
    if (password !== confirm) {
      setError(t("passwordsMismatch"));
      return;
    }
    setBusy(true);
    try {
      const result = await activateWorkspace(token, password);
      setDone({ slug: result.slug, workspaceHost: result.workspaceHost });
    } catch (e) {
      setError(e instanceof ApiError ? errorMessage(e) : t("activateFailed"));
      setBusy(false);
    }
  }

  if (done) {
    // The tenant lives at {branch}.{customer} under the base domain the marketing site is served on, so
    // prepend the workspace host to the current apex host to reach its sign-in.
    const loginUrl = done.workspaceHost
      ? `${window.location.protocol}//${done.workspaceHost}.${window.location.host}/login`
      : "/login";
    return (
      <AuthLayout title={t("activateDoneTitle")} description={t("activateDoneDesc", { slug: done.slug })}>
        <div className="space-y-4 text-center">
          <CheckCircle2 className="mx-auto size-10 text-primary" />
          <p className="text-sm text-muted-foreground">
            <Trans t={t} i18nKey="activateDoneBody" values={{ slug: done.slug }}
                   components={{ b: <span className="font-medium text-foreground" /> }} />
          </p>
          {done.workspaceHost && (
            <p className="text-sm text-muted-foreground">
              <Trans t={t} i18nKey="activateDoneAddress" values={{ host: `${done.workspaceHost}.${window.location.host}` }}
                     components={{ b: <span className="font-mono font-medium text-foreground" /> }} />
            </p>
          )}
          <Button className="w-full" onClick={() => { window.location.href = loginUrl; }}>
            {t("continueToSignIn")}
          </Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={t("activateTitle")} description={t("activateDescription")}>
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="password">{t("adminPassword")}</Label>
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
          {t("createWorkspace")}
        </Button>
      </form>
      <p className="mt-3 text-xs text-muted-foreground">
        {t("activateHint")}
      </p>
    </AuthLayout>
  );
}
