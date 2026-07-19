import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { KeyRound, Loader2, LogIn } from "lucide-react";
import { ApiError } from "../api";
import { getSession, identify, logout, startFederation } from "../auth";
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
  const { t } = useTranslation("auth");
  const org = session.org;
  const passkeyOffered = session.passwordlessLoginAllowed && webAuthnSupported();
  const providers = session.federationProviders ?? [];
  const hasAlternatives = passkeyOffered || providers.length > 0;
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

  // A failed federated login (the /callback could not establish a session) redirects here with a flag; show a
  // generic message and strip the flag so a reload doesn't repeat it. Non-revealing — the reason stays server-side.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get("login_error") === "federation") {
      setError(t("loginFederationFailed"));
      params.delete("login_error");
      const query = params.toString();
      window.history.replaceState({}, "", window.location.pathname + (query ? `?${query}` : ""));
    }
  }, [t]);

  async function signInWithPasskey() {
    setError(null);
    setPasskeyBusy(true);
    try {
      onDone(await passwordlessLogin());
    } catch {
      setError(t("loginPasskeyFailed"));
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
        setError(e.status === 404 ? t("loginNoAccount") : t("loginStartFailed"));
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
      title={t("loginTitle")}
      description={t("loginDescription")}
      org={org}
      onBack={useDifferentOrg}
      backLabel={t("useDifferentOrg")}
    >
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="email">{t("emailLabel")}</Label>
          <Input id="email" type="email" value={email} autoFocus required
                 autoComplete="username webauthn"
                 placeholder={t("loginEmailPlaceholder")} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          {t("continue")}
        </Button>
      </form>

      {hasAlternatives && (
        <>
          <div className="my-4 flex items-center gap-3 text-xs text-muted-foreground">
            <span className="h-px flex-1 bg-border" />
            {t("or")}
            <span className="h-px flex-1 bg-border" />
          </div>
          <div className="space-y-2">
            {passkeyOffered && (
              <Button type="button" variant="outline" className="w-full" disabled={passkeyBusy}
                      onClick={signInWithPasskey}>
                {passkeyBusy ? <Loader2 className="animate-spin" /> : <KeyRound />}
                {t("signInWithPasskey")}
              </Button>
            )}
            {providers.map((provider) => (
              <Button key={provider.alias} type="button" variant="outline" className="w-full"
                      onClick={() => startFederation(provider.alias)}>
                <LogIn /> {t("signInWithProvider", { provider: provider.displayName })}
              </Button>
            ))}
          </div>
        </>
      )}

      <p className="mt-3 text-xs text-muted-foreground">
        {t("loginPolicyHint")}
      </p>
    </AuthLayout>
  );
}
