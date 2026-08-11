import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowUpRight, Check, KeyRound, Loader2, Lock, ShieldCheck } from "lucide-react";
import { getConsent } from "@/consent";
import type { ConsentModel } from "@/consent";
import { csrfToken } from "@/api";
import AuthLayout from "@/components/layout/AuthLayout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

/**
 * The OAuth2 authorization-consent screen — the one screen in this product an end user of a CONNECTED
 * application sees. The authorization endpoint redirects here with the requesting client and the scopes;
 * the user's selection posts straight back to `/oauth2/authorize`, which owns the grant.
 *
 * <p>It lives in the SPA rather than a server-side template so it shares the one language toggle, the one
 * theme, and the one branding source with every other screen. As a Thymeleaf page it was the only screen
 * that could not switch language at all, because the server-side resolver read `Accept-Language` and
 * refused to be set — a symptom of the split, not a bug in the page.
 */

/** Scopes that stay OFF until the user turns them on: durable access is not something to pre-tick. */
const OPT_IN_SCOPES = ["offline_access"];

const AUTHORIZE_ENDPOINT = "/oauth2/authorize";
const ALLOW_FORM = "allow-form";
const CANCEL_FORM = "cancel-form";

export default function Consent() {
  const { t } = useTranslation("auth");
  const params = new URLSearchParams(window.location.search);
  const clientId = params.get("client_id") ?? "";
  const scope = params.get("scope") ?? "";
  const state = params.get("state") ?? "";
  const userCode = params.get("user_code");

  const [model, setModel] = useState<ConsentModel | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getConsent(clientId, scope).then(setModel).catch(() => setError(t("consentLoadFailed")));
  }, [clientId, scope, t]);

  if (error) {
    return (
      <AuthLayout step={t("consentStep")} title={t("consentTitle")}>
        <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>
      </AuthLayout>
    );
  }
  if (!model) {
    return (
      <AuthLayout step={t("consentStep")} title={t("consentTitle")}>
        <div className="flex justify-center py-4"><Loader2 className="animate-spin" /></div>
      </AuthLayout>
    );
  }

  // The same hidden fields on both forms. Cancel differs only by carrying no scopes, which is what the
  // authorization endpoint reads as a denial — so the two must never share one <form>.
  const hidden = (
    <>
      <input type="hidden" name="client_id" value={clientId} />
      <input type="hidden" name="state" value={state} />
      {userCode && <input type="hidden" name="user_code" value={userCode} />}
      <input type="hidden" name="_csrf" value={csrfToken()} />
    </>
  );

  return (
    <AuthLayout screen="CONSENT"
      wide
      step={t("consentStep")}
      title={t("consentHeading", { client: model.clientName })}
      description={t("consentLead")}
    >
      {model.redirectHost && (
        <div className="mb-7 rounded-lg border bg-muted/40 px-4 py-3">
          <p className="text-xs text-muted-foreground">{t("consentDestinationLabel")}</p>
          <p className="mt-1.5 flex items-center gap-2 break-all font-mono text-sm font-medium">
            <ArrowUpRight className="size-4 shrink-0 text-primary" />
            {model.redirectHost}
          </p>
        </div>
      )}

      <p className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        {t("consentPermissionsLabel")}
      </p>

      <form id={ALLOW_FORM} method="post" action={AUTHORIZE_ENDPOINT}>
        {hidden}
        {/* Between items must breathe more than within one, or the list reads as a single paragraph. */}
        <ul className="space-y-2.5">
          {/* openid is granted by the act of signing in; showing it as a toggle would imply it is optional. */}
          <li className="flex items-start gap-3 rounded-lg border bg-muted/30 px-4 py-3.5">
            <Lock className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <div className="min-w-0">
              <p className="text-sm font-medium leading-snug">{t("consentOpenidTitle")}</p>
              <p className="mt-1.5 text-xs leading-relaxed text-muted-foreground">{t("consentOpenidDesc")}</p>
            </div>
          </li>
          {model.toApprove.map((item) => (
            <li key={item.scope}>
              <label className="flex cursor-pointer items-start gap-3 rounded-lg border px-4 py-3.5 transition-colors hover:bg-muted/40">
                <input
                  type="checkbox"
                  name="scope"
                  value={item.scope}
                  defaultChecked={!OPT_IN_SCOPES.includes(item.scope)}
                  className="mt-0.5 size-4 shrink-0 accent-primary"
                />
                <div className="min-w-0">
                  <p className="text-sm font-medium leading-snug">{item.description}</p>
                  <p className="mt-1.5 font-mono text-xs text-muted-foreground">{item.scope}</p>
                </div>
              </label>
            </li>
          ))}
        </ul>
      </form>

      {model.previouslyGranted.length > 0 && (
        <div className="mt-7 border-t pt-6">
          <p className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("consentGrantedLabel")}
          </p>
          <ul className="space-y-2.5">
            {model.previouslyGranted.map((item) => (
              <li key={item.scope} className="flex items-start gap-3 text-sm text-muted-foreground">
                <Check className="mt-0.5 size-4 shrink-0 text-success" />
                <span className="leading-snug">{item.description}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* 12px icons read as specks, not marks. These sit at the same size as the text they introduce. */}
      <ul className="mt-7 space-y-3 border-t pt-6 text-[13px] text-muted-foreground">
        <li className="flex items-start gap-3">
          <KeyRound className="mt-px size-4 shrink-0 text-muted-foreground/70" />
          <span className="leading-snug">{t("consentReassurePassword")}</span>
        </li>
        <li className="flex items-start gap-3">
          <ShieldCheck className="mt-px size-4 shrink-0 text-muted-foreground/70" />
          <span className="leading-snug">{t("consentReassureUnchecked")}</span>
        </li>
        <li className="flex items-start gap-3">
          <ShieldCheck className="mt-px size-4 shrink-0 text-muted-foreground/70" />
          <span className="leading-snug">{t("consentReassureRevoke")}</span>
        </li>
      </ul>

      {/* Denial is a POST with no scopes, so it needs its own form — hence the button/form pairing. */}
      <form id={CANCEL_FORM} method="post" action={AUTHORIZE_ENDPOINT}>{hidden}</form>
      <div className="mt-7 flex gap-3">
        <Button type="submit" form={CANCEL_FORM} variant="outline" className="flex-1">{t("consentCancel")}</Button>
        <Button type="submit" form={ALLOW_FORM} className="flex-1">{t("consentAllow")}</Button>
      </div>
    </AuthLayout>
  );
}
