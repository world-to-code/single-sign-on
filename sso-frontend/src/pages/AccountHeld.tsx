import { useTranslation } from "react-i18next";
import { ShieldAlert } from "lucide-react";
import { getSession, logout } from "@/auth";
import type { SessionView } from "@/auth";
import AuthLayout from "@/components/layout/AuthLayout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

/**
 * Terminal state for a sign-in the server refused: a hold is in force on the account and it has no second
 * factor to satisfy it with, so the password alone cannot finish (next === "ACCOUNT_HELD").
 *
 * <p>There is nothing to submit here on purpose. Anything this screen could offer — resend, retry, enrol a
 * factor now — would be a way past the hold using only the credential the hold exists to distrust. The way
 * out is an administrator, and a hold expires by itself.
 *
 * <p>The wording is deliberately not a tenant's to customise: it states a security decision, and a
 * reassuring rewrite of it would be a reassuring lie.
 */
export default function AccountHeld({ session, onDone }:
  { session: SessionView; onDone: (s: SessionView) => void }) {
  const { t } = useTranslation("auth");

  // Drop the half-authenticated session on the way back, so the refused attempt leaves nothing behind.
  async function back() {
    try { await logout(); } catch { /* the session is being abandoned either way */ }
    onDone(await getSession());
  }

  return (
    <AuthLayout
      title={t("accountHeldTitle")}
      description={t("accountHeldDescription")}
      org={session.org}
      onBack={back}
      backLabel={t("accountHeldBack")}
    >
      <Alert className="mb-4">
        <ShieldAlert />
        <AlertDescription>{t("accountHeldGuidance")}</AlertDescription>
      </Alert>

      <Button type="button" variant="outline" className="w-full" onClick={back}>
        {t("accountHeldBack")}
      </Button>
    </AuthLayout>
  );
}
