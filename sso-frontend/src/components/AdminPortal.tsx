import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { Loader2, ShieldAlert } from "lucide-react";
import type { SessionView } from "@/auth";
import { handleAdminCallback, isAdminUnlocked, startAdminOidc } from "@/adminPortal";
import { triggerStepUp } from "@/api";
import LoadingScreen from "@/components/LoadingScreen";
import AuthLayout from "@/components/layout/AuthLayout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

/**
 * Gate for /admin/*: only ROLE_ADMIN may enter, and only after completing the admin-console OIDC
 * step-up flow. If the admin is not yet unlocked, kick off the real authorization-code + PKCE flow
 * (which forces step-up at the IdP); non-admins are bounced back to the user portal.
 */
export function AdminGuard({ session, children }: { session: SessionView; children: ReactNode }) {
  const isAdmin = session.roles.includes("ROLE_ADMIN");
  const unlocked = isAdminUnlocked();

  useEffect(() => {
    if (isAdmin && !unlocked) {
      // Force a FRESH step-up re-auth FIRST (re-stamps the session auth_time), THEN run the OIDC flow
      // so the minted elevation token carries a fresh auth_time (RFC 9470). Bounce home if declined.
      void (async () => {
        if (await triggerStepUp("action")) {
          await startAdminOidc(); // navigates away to /oauth2/authorize
        } else {
          window.location.replace("/");
        }
      })();
    }
  }, [isAdmin, unlocked]);

  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }
  if (!unlocked) {
    return <LoadingScreen />; // briefly shown while stepping up / redirecting to the IdP
  }
  return <>{children}</>;
}

/**
 * /admin/callback: completes the OIDC code exchange, stores the admin elevation token, and enters
 * the admin console. The step-up that freshens the token's auth_time already ran BEFORE the OIDC flow
 * was started (AppShell button / AdminGuard), so here we only exchange the code. Errors show a retry.
 */
export function AdminCallback() {
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    handleAdminCallback()
      .then(() => {
        window.location.replace("/admin");
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : "Could not complete admin sign-in."));
  }, []);

  if (error) {
    return (
      <AuthLayout step="Admin console" title="Admin sign-in failed"
                  description="The step-up verification could not be completed.">
        <Alert variant="destructive" className="mb-4">
          <ShieldAlert className="size-4" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
        <Button className="w-full" onClick={() => {
          void (async () => { if (await triggerStepUp("action")) await startAdminOidc(); })();
        }}>Try again</Button>
        <a href="/" className="mt-4 block text-center text-sm text-muted-foreground hover:text-foreground">
          Back to portal
        </a>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout step="Admin console" title="Verifying…"
                description="Completing the secure admin sign-in.">
      <div className="flex justify-center py-4"><Loader2 className="animate-spin" /></div>
    </AuthLayout>
  );
}
