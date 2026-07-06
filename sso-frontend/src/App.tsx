import { useCallback, useEffect, useState } from "react";
import { getSession, resume } from "./auth";
import type { SessionView } from "./auth";
import OrgSelect from "./pages/OrgSelect";
import Login from "./pages/Login";
import MfaStep from "./pages/MfaStep";
import AppStepUp from "./pages/AppStepUp";
import SetPassword from "./pages/SetPassword";
import MarketingSite, { MARKETING_PATHS } from "./MarketingSite";
import Signup from "./pages/Signup";
import Activate from "./pages/Activate";
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
        factors: [], roles: [], permissions: [], next: "ORGANIZATION", pendingFactors: [],
        mfaEnrollmentAllowed: true, org: null,
      }))
      .finally(() => setLoading(false));
  }, [apply]);

  // Public onboarding invitation landing — reached from an emailed link with no session; render it before
  // the session/loading gate so an invited admin can set their password without signing in first.
  if (window.location.pathname === "/set-password") {
    return <SetPassword />;
  }

  // Public self-service signup — a prospective customer applies for a workspace; no session required.
  if (window.location.pathname === "/signup") {
    return <Signup />;
  }

  // Public signup verification landing — the emailed link that actually creates the workspace on redeem.
  if (window.location.pathname === "/activate") {
    return <Activate />;
  }

  if (loading || !session) {
    return <LoadingScreen />;
  }

  // Per-app step-up: a signed-in user redirected here must clear extra factors, then resume.
  if (session.next === "DONE" && window.location.pathname === "/stepup") {
    return <AppStepUp />;
  }

  // Public marketing site for an organic signed-out visit to a marketing path. RP-initiated OIDC/SAML logins
  // are bounced to /login (never a marketing path), so this never intercepts them.
  if (session.next === "ORGANIZATION" && MARKETING_PATHS.includes(window.location.pathname)) {
    return <MarketingSite />;
  }

  switch (session.next) {
    case "ORGANIZATION":
      return <OrgSelect onDone={apply} />;
    case "IDENTIFY":
      return <Login session={session} onDone={apply} />;
    case "FACTOR":
      // A signed-out user who hasn't identified yet should see the entry screen, not a bare factor.
      return session.username
        ? <MfaStep session={session} onDone={apply} />
        : <Login session={session} onDone={apply} />;
    case "DONE":
      return <Console session={session} />;
  }
}
