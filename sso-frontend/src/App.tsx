import { useCallback, useEffect, useState } from "react";
import { getSession, resume } from "./auth";
import type { SessionView } from "./auth";
import Login from "./pages/Login";
import MfaStep from "./pages/MfaStep";
import AppStepUp from "./pages/AppStepUp";
import Console from "./Console";
import LoadingScreen from "./components/LoadingScreen";

export default function App() {
  const [session, setSession] = useState<SessionView | null>(null);
  const [loading, setLoading] = useState(true);

  const apply = useCallback((next: SessionView) => {
    setSession(next);
    if (next.next === "DONE") {
      // Resume an in-flight external OIDC/SAML request, if any.
      resume()
        .then((r) => {
          if (r.redirectUrl && r.redirectUrl !== "/") {
            window.location.href = r.redirectUrl;
          }
        })
        .catch(() => undefined);
    }
  }, []);

  useEffect(() => {
    getSession()
      .then(apply)
      // A failed probe must not hang on "Loading…" — fall back to the entry screen so the user can sign in.
      .catch(() => setSession({
        authenticated: false, username: null, totpEnrolled: false, fido2Enrolled: false,
        factors: [], roles: [], next: "IDENTIFY", pendingFactors: [], mfaEnrollmentAllowed: true,
      }))
      .finally(() => setLoading(false));
  }, [apply]);

  if (loading || !session) {
    return <LoadingScreen />;
  }

  // Per-app step-up: a signed-in user redirected here must clear extra factors, then resume.
  if (session.next === "DONE" && window.location.pathname === "/stepup") {
    return <AppStepUp />;
  }

  switch (session.next) {
    case "IDENTIFY":
      return <Login onDone={apply} />;
    case "FACTOR":
      // A signed-out user who hasn't identified yet should see the entry screen, not a bare factor.
      return session.username ? <MfaStep session={session} onDone={apply} /> : <Login onDone={apply} />;
    case "DONE":
      return <Console session={session} />;
  }
}
