import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Fingerprint, Loader2, Lock } from "lucide-react";
import { getStepUp } from "../portal";
import type { StepUpInfo } from "../portal";
import { webAuthnSupported } from "../webauthn";
import { factorMeta } from "../factors";
import { useFactorVerification } from "../hooks/useFactorVerification";
import AuthLayout from "../components/layout/AuthLayout";
import { FactorChooser } from "../components/auth/FactorChooser";
import { OtpInput } from "../components/auth/OtpInput";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";

/**
 * Per-app step-up: the app the user is launching requires extra factors. Collect the missing factor
 * (which grants it into the session) and resume the original request.
 */
export default function AppStepUp() {
  const { t } = useTranslation("auth");
  const [info, setInfo] = useState<StepUpInfo | null>(null);
  const {
    factor, setFactor, code, setCode, password, setPassword, codeSent,
    error, setError, busy, submitCode, submitPassword, sendCode, fido2,
  } = useFactorVerification({ initialFactor: "", onSuccess: () => refresh() });

  // After loading (and after each grant) check whether step-up is satisfied: if so resume the
  // original request, otherwise show the next pending factor.
  const refresh = useCallback(async () => {
    const i = await getStepUp();
    if (i.ready) { window.location.href = i.returnUrl || "/"; return; }
    setInfo(i);
    setFactor((f) => (i.pendingFactors.includes(f) ? f : i.pendingFactors[0]));
  }, [setFactor]);

  useEffect(() => { refresh().catch(() => setError(t("stepUpLoadFailed"))); }, [refresh, setError, t]);

  if (!info) {
    return <AuthLayout step={t("stepUpStep")} title={t("stepUpChecking")}><div className="flex justify-center py-4"><Loader2 className="animate-spin" /></div></AuthLayout>;
  }

  const Icon = factorMeta(factor).icon;
  return (
    <AuthLayout step={t("stepUpStep")} title={t("stepUpTitle")}
                description={t("stepUpDescription")}>
      <FactorChooser factors={info.pendingFactors} value={factor} onSelect={setFactor} />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      {factor === "FIDO2" && (
        <Button type="button" className="w-full" onClick={fido2} disabled={busy || !webAuthnSupported()}>
          {busy ? <Loader2 className="animate-spin" /> : <Fingerprint />} {t("usePasskey")}
        </Button>
      )}
      {factor === "PASSWORD" && (
        <form onSubmit={submitPassword} className="space-y-3">
          <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t("passwordPlaceholder")} autoFocus required />
          <Button type="submit" className="w-full" disabled={busy}>{busy ? <Loader2 className="animate-spin" /> : <Lock />} {t("verify")}</Button>
        </form>
      )}
      {(factor === "EMAIL" || factor === "SMS") && !codeSent && (
        <Button type="button" className="w-full" onClick={sendCode}>
          <Icon /> {t(factor === "SMS" ? "smsMeCode" : "emailMeCode")}
        </Button>
      )}
      {(factor === "TOTP" || ((factor === "EMAIL" || factor === "SMS") && codeSent)) && (
        <form onSubmit={submitCode} className="space-y-3">
          <OtpInput value={code} onChange={(e) => setCode(e.target.value)} />
          <Button type="submit" className="w-full" disabled={busy}>{busy ? <Loader2 className="animate-spin" /> : <Icon />} {t("verify")}</Button>
        </form>
      )}

      <a href={info.returnUrl || "/"} className="mt-4 block text-center text-sm text-muted-foreground hover:text-foreground">{t("cancel")}</a>
    </AuthLayout>
  );
}
