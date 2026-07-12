import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Navigate } from "react-router-dom";
import { Loader2, ShieldAlert } from "lucide-react";
import { handleAdminCallback, isAdminUnlocked, startAdminOidc } from "@/adminPortal";
import { triggerStepUp } from "@/api";
import { useAdminConsoleAccess } from "@/hooks/useAdminConsoleAccess";
import LoadingScreen from "@/components/LoadingScreen";
import AuthLayout from "@/components/layout/AuthLayout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

/**
 * Gate for /admin/*: entry is an APP ASSIGNMENT (Model B), not a role — only a user assigned the
 * admin-console app may enter, and only after completing the admin-console OIDC step-up flow. If not
 * yet unlocked, kick off the real authorization-code + PKCE flow (which forces step-up at the IdP);
 * unassigned users are bounced back to the user portal. (The backend independently enforces the same
 * assignment at /oauth2/authorize, so this is UX, not the security boundary.)
 */
export function AdminGuard({ children }: { children: ReactNode }) {
  const canEnter = useAdminConsoleAccess(); // undefined while loading
  const unlocked = isAdminUnlocked();
  const started = useRef(false); // guard against React StrictMode running the effect twice (double OIDC flow)
  const [declined, setDeclined] = useState(false);

  useEffect(() => {
    if (canEnter === true && !unlocked && !started.current && !declined) {
      started.current = true;
      // Force a FRESH step-up re-auth FIRST (re-stamps the session auth_time), THEN run the OIDC flow
      // so the minted elevation token carries a fresh auth_time (RFC 9470).
      void (async () => {
        if (await triggerStepUp("elevation")) {
          await startAdminOidc(); // navigates away to /oauth2/authorize
        } else {
          // Declining re-elevation is NOT an authorization failure — it's just an unmet extra-auth
          // requirement. Keep the user in the admin console context with a retry, rather than ejecting
          // them to the user portal.
          setDeclined(true);
        }
      })();
    }
  }, [canEnter, unlocked, declined]);

  if (canEnter === undefined) {
    return <LoadingScreen />; // resolving assignment
  }
  if (!canEnter) {
    return <Navigate to="/" replace />;
  }
  if (declined) {
    return <ReElevatePrompt onRetry={() => { started.current = false; setDeclined(false); }} />;
  }
  if (!unlocked) {
    return <LoadingScreen />; // briefly shown while stepping up / redirecting to the IdP
  }
  return <>{children}</>;
}

/** Shown when the admin session needs a fresh re-elevation and the user declined — stay in context, offer retry. */
function ReElevatePrompt({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation("auth");
  return (
    <AuthLayout step={t("adminGateStep")} title={t("adminGateTitle")} description={t("adminGateDescription")}>
      <Alert className="mb-4">
        <ShieldAlert className="size-4" />
        <AlertDescription>{t("adminGateAlert")}</AlertDescription>
      </Alert>
      <Button className="w-full" onClick={onRetry}>{t("adminGateRetry")}</Button>
      <a href="/" className="mt-4 block text-center text-sm text-muted-foreground hover:text-foreground">
        {t("adminGateBackToPortal")}
      </a>
    </AuthLayout>
  );
}

/**
 * /admin/callback: completes the OIDC code exchange, stores the admin elevation token, and enters
 * the admin console. The step-up that freshens the token's auth_time already ran BEFORE the OIDC flow
 * was started (AppShell button / AdminGuard), so here we only exchange the code. Errors show a retry.
 */
export function AdminCallback() {
  const { t } = useTranslation("auth");
  // `detail` is the server's own message when there is one; null falls back to translated copy.
  const [failure, setFailure] = useState<{ detail: string | null } | null>(null);
  const ran = useRef(false); // the authorization code is single-use — never exchange it twice (StrictMode)

  useEffect(() => {
    if (ran.current) {
      return;
    }
    ran.current = true;
    handleAdminCallback()
      .then(() => {
        window.location.replace("/admin");
      })
      .catch((e: unknown) => setFailure({ detail: e instanceof Error ? e.message : null }));
  }, []);

  if (failure) {
    return (
      <AuthLayout step={t("adminGateStep")} title={t("adminCallbackFailedTitle")}
                  description={t("adminCallbackFailedDesc")}>
        <Alert variant="destructive" className="mb-4">
          <ShieldAlert className="size-4" />
          <AlertDescription>{failure.detail ?? t("adminCallbackFailedFallback")}</AlertDescription>
        </Alert>
        <Button className="w-full" onClick={() => {
          void (async () => { if (await triggerStepUp("elevation")) await startAdminOidc(); })();
        }}>{t("adminCallbackRetry")}</Button>
        <a href="/" className="mt-4 block text-center text-sm text-muted-foreground hover:text-foreground">
          {t("adminGateBackToPortal")}
        </a>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout step={t("adminGateStep")} title={t("adminCallbackVerifyingTitle")}
                description={t("adminCallbackVerifyingDesc")}>
      <div className="flex justify-center py-4"><Loader2 className="animate-spin" /></div>
    </AuthLayout>
  );
}
