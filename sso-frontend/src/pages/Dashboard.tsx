import { Link } from "react-router-dom";
import { KeyRound, UserCircle } from "lucide-react";
import type { SessionView } from "../auth";
import { Metric } from "../components/Metric";
import { PageHeader } from "../components/PageHeader";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";

/**
 * An enrollment state, not a figure — so it reads as a state chip rather than a Metric. The dot is
 * hollow when the factor is missing, so the state survives without colour (DESIGN.md §4).
 */
function FactorStat({ label, value, enrolled }: { label: string; value: string; enrolled: boolean }) {
  return (
    <Card>
      <CardContent className="p-5">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
        <p className="mt-2 flex items-center gap-2 text-lg font-semibold">
          <span
            aria-hidden
            className={`size-2 rounded-full ${enrolled ? "bg-allow" : "ring-2 ring-inset ring-faint"}`}
          />
          <span className={enrolled ? "" : "text-muted-foreground"}>{value}</span>
        </p>
      </CardContent>
    </Card>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b py-2 last:border-0">
      <span className="text-muted-foreground">{label}</span>
      {children}
    </div>
  );
}

export default function Dashboard({ session }: { session: SessionView }) {
  return (
    <>
      <PageHeader title={`Welcome, ${session.username}`} description="Your identity and security status at a glance." />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <FactorStat label="Authenticator (TOTP)" enrolled={session.totpEnrolled}
                    value={session.totpEnrolled ? "Enrolled" : "Not set up"} />
        <FactorStat label="Passkey (FIDO2)" enrolled={session.fido2Enrolled}
                    value={session.fido2Enrolled ? "Registered" : "None"} />
        <Metric label="Factors this session" value={session.factors.length} />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><UserCircle className="size-4 text-primary" /> Identity</CardTitle>
            <CardDescription>Details of your current session.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <Row label="User"><span className="font-medium">{session.username}</span></Row>
            <Row label="Roles">
              <div className="flex flex-wrap gap-1">
                {session.roles.length ? session.roles.map((r) => <Badge key={r} variant="secondary">{r}</Badge>)
                  : <span className="text-muted-foreground">—</span>}
              </div>
            </Row>
            <Row label="Factors satisfied">
              <div className="flex flex-wrap justify-end gap-1">
                {session.factors.length ? session.factors.map((f) => <Badge key={f} variant="success">{f.replace("FACTOR_", "")}</Badge>)
                  : <span className="text-muted-foreground">—</span>}
              </div>
            </Row>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><KeyRound className="size-4 text-primary" /> Security keys</CardTitle>
            <CardDescription>One passkey covers passwordless sign-in and your FIDO2 policy step.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button asChild variant="outline"><Link to="/passkeys"><KeyRound /> Manage my passkeys</Link></Button>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
